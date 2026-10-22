package io.stintflow.sandbox.billing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.cloudevents.CloudEvent;
import com.fasterxml.jackson.databind.JsonNode;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.mailer.Mailer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.DynamoDbStateStore;
import io.stintflow.aws.EventBridgeEventPublisher;
import io.stintflow.aws.EventBridgeSchedulerService;
import io.stintflow.aws.EventBridgeSchedulerTimerService;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.aws.S3FactBlobStore;
import io.stintflow.aws.SqsDelayTimerService;
import io.stintflow.aws.SqsDomainEventSource;
import io.stintflow.aws.SqsTaskTransport;
import io.stintflow.core.DelayRoutingTimerService;
import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.WorkflowRegistry;
import io.stintflow.core.expr.JqExpressionEvaluator;
import io.stintflow.core.interpreter.TreeInterpreter;
import io.stintflow.core.schedule.ScheduleReconciler;
import io.stintflow.core.schedule.ScheduledStart;
import io.stintflow.core.trigger.DomainEventRouter;
import io.stintflow.core.trigger.ResumeReaction;
import io.stintflow.core.trigger.StartReaction;
import io.stintflow.core.trigger.TriggerBindings;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.sandbox.billing.aws.DynamoDbEffectMarkers;
import io.stintflow.sandbox.billing.aws.DynamoDbInvoiceRecords;
import io.stintflow.sandbox.billing.aws.QuarkusMailSender;
import io.stintflow.sandbox.billing.aws.S3InvoiceDocuments;
import io.stintflow.sandbox.billing.chaos.Chaos;
import io.stintflow.sandbox.billing.chaos.ChaosEventPublisher;
import io.stintflow.sandbox.billing.chaos.ChaosTaskHandler;
import io.stintflow.sandbox.billing.worker.FailureLog;
import io.stintflow.sandbox.billing.worker.BillingHandlers;
import io.stintflow.sandbox.billing.worker.FaultInjection;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.EventPublisher;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.aws.worker.SqsWorkerBinding;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.1: the Billing domain's own engine, over Stint's AWS connectors only (no stint-inmemory). It is built like
 * Stint's {@code stint-example-aws-smoke} (SDD 2.7), from the clients the Quarkus extensions produce:
 * <ul>
 *   <li>state, waits, outbox and inbox in the domain's DynamoDB tables;</li>
 *   <li>tasks over the domain's SQS queues, served by Stint's SqsWorkerBinding with a WorkerPool (S.5 RF9);</li>
 *   <li>timers composed from SQS (up to 15 min) and EventBridge Scheduler;</li>
 *   <li>facts on the {@code sandbox-domain} EventBridge bus through the outbox, swept every 30 s;</li>
 *   <li>inbound domain events from the domain's SQS queue, started ({@code schedule.on}) or resumed
 *       ({@code listen});</li>
 *   <li>cron schedules reconciled into EventBridge Scheduler;</li>
 *   <li>tracing through stint-otel.</li>
 * </ul>
 * SDD S.2: the worker runs Billing's handlers ({@link BillingHandlers}) over {@code sandbox-facts}, the
 * {@code billing-invoices}/{@code billing-effects} tables and SMTP, and logs every failed result as it travels
 * on the wire (type and status, CA3).
 * The connectors are built by hand, so nothing here reads Stint's global {@code stint.aws.*} properties: two
 * domains can never share a resource by accident. {@code billing.engine.enabled} / {@code billing.worker.enabled}
 * let the same jar run either role alone (SDD S.1, sec. 8d).
 * SDD S.4, 8b: {@code billing.chaos.halt-before-publish} and {@code billing.chaos.halt-in-task} (unset by default)
 * mount the chaos points of the RF2 scenarios; unset, the engine and the worker are exactly as without them.
 */
@ApplicationScoped
public class BillingEngine {

    private static final Logger LOG = Logger.getLogger(BillingEngine.class);

    @Inject
    SqsClient sqs;
    @Inject
    DynamoDbClient ddb;
    @Inject
    S3Client s3;
    @Inject
    EventBridgeClient eventBridge;
    @Inject
    SchedulerClient scheduler;
    @Inject
    OpenTelemetry openTelemetry;
    @Inject
    Mailer mailer;

    @ConfigProperty(name = "billing.sqs.tasks-queue-url")
    String tasksQueue;
    @ConfigProperty(name = "billing.sqs.results-queue-url")
    String resultsQueue;
    @ConfigProperty(name = "billing.sqs.timers-queue-url")
    String timersQueue;
    @ConfigProperty(name = "billing.sqs.schedules-queue-url")
    String schedulesQueue;
    @ConfigProperty(name = "billing.sqs.domain-events-queue-url")
    String domainEventsQueue;
    @ConfigProperty(name = "billing.sqs.timers-queue-arn")
    String timersQueueArn;
    @ConfigProperty(name = "billing.sqs.schedules-queue-arn")
    String schedulesQueueArn;
    @ConfigProperty(name = "billing.dynamodb.instances-table")
    String instancesTable;
    @ConfigProperty(name = "billing.dynamodb.waits-table")
    String waitsTable;
    @ConfigProperty(name = "billing.dynamodb.outbox-table")
    String outboxTable;
    @ConfigProperty(name = "billing.dynamodb.inbox-table")
    String inboxTable;
    @ConfigProperty(name = "billing.s3.blobs-bucket")
    String blobsBucket;
    @ConfigProperty(name = "billing.s3.facts-bucket")
    String factsBucket;
    @ConfigProperty(name = "billing.eventbridge.domain-bus")
    String domainBus;
    @ConfigProperty(name = "billing.scheduler.schedules-group")
    String schedulesGroup;
    @ConfigProperty(name = "billing.scheduler.timers-group")
    String timersGroup;
    @ConfigProperty(name = "billing.scheduler.role-arn")
    String roleArn;
    @ConfigProperty(name = "billing.scheduler.sent-timestamp-fallback", defaultValue = "false")
    boolean sentTimestampFallback;
    @ConfigProperty(name = "billing.dynamodb.invoices-table")
    String invoicesTable;
    @ConfigProperty(name = "billing.dynamodb.effects-table")
    String effectsTable;
    @ConfigProperty(name = "billing.mail.from")
    String mailFrom;
    @ConfigProperty(name = "billing.mail.to")
    String mailTo;
    @ConfigProperty(name = "billing.fault.slow-delay", defaultValue = "5s")
    Duration slowDelay;
    @ConfigProperty(name = "billing.engine.enabled", defaultValue = "true")
    boolean engineEnabled;
    @ConfigProperty(name = "billing.worker.enabled", defaultValue = "true")
    boolean workerEnabled;
    /** SDD S.4, RF2b: an event type; the engine halts on its first publication. Unset by default. */
    @ConfigProperty(name = "billing.chaos.halt-before-publish")
    Optional<String> haltBeforePublish;
    /** SDD S.4, RF2a: a task route; the worker halts after its first task's effect. Unset by default. */
    @ConfigProperty(name = "billing.chaos.halt-in-task")
    Optional<String> haltInTask;

    private volatile WorkflowEngine engine;
    private volatile WorkflowRegistry registry;
    private volatile ScheduledStart scheduledStart;
    private volatile ExecutionTracer tracer = ExecutionTracer.NOOP;
    private volatile boolean workerRunning;
    /** SDD S.5, RF9: Stint's SQS worker binding (3a.7) with a WorkerPool, in place of the serial loop of S.1-S.4. */
    private volatile SqsWorkerBinding worker;
    @ConfigProperty(name = "billing.worker.concurrency", defaultValue = "8")
    int workerConcurrency;

    void onStart(@Observes StartupEvent event) throws Exception {
        tracer = new OpenTelemetryExecutionTracer(openTelemetry);
        BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        if (engineEnabled) {
            startEngine(blobs);
        }
        if (workerEnabled) {
            startWorker(blobs);
        }
        LOG.infof("Billing domain started: engine=%s worker=%s, over %s, %s, %s, %s, %s", engineEnabled, workerEnabled,
                DynamoDbStateStore.class.getSimpleName(), SqsTaskTransport.class.getSimpleName(),
                DelayRoutingTimerService.class.getSimpleName(), EventBridgeEventPublisher.class.getSimpleName(),
                SqsDomainEventSource.class.getSimpleName());
    }

    private void startEngine(BlobStore blobs) throws Exception {
        DelayRoutingTimerService timers = new DelayRoutingTimerService(new SqsDelayTimerService(sqs, timersQueue),
                new EventBridgeSchedulerTimerService(scheduler, timersGroup, roleArn, timersQueueArn,
                        Duration.ofDays(365)), InstantSource.system());
        WorkflowRegistry registry = new WorkflowRegistry(timers.maxDelay());
        List<WorkflowDefinition> definitions = new DslLoader().loadClasspath("stint/workflows/*.yaml", LoadOptions.STRICT);
        definitions.forEach(registry::register);

        WorkflowEngine built = new WorkflowEngine(registry, new SqsTaskTransport(sqs, tasksQueue, resultsQueue),
                new DynamoDbStateStore(ddb, instancesTable, waitsTable, outboxTable, 4, inboxTable),
                timers, blobs, new TreeInterpreter(new JqExpressionEvaluator()), InstantSource.system(),
                publisher(new EventBridgeEventPublisher(eventBridge, domainBus, "default"), haltBeforePublish, Chaos.HALT),
                new S3FactBlobStore(s3, factsBucket),
                tracer);
        built.outboxRelay().start(Duration.ofSeconds(30));

        EventBridgeSchedulerService schedules = new EventBridgeSchedulerService(scheduler, sqs, schedulesGroup, roleArn,
                schedulesQueueArn, schedulesQueue, sentTimestampFallback);
        new ScheduleReconciler(registry, schedules).reconcile().toCompletableFuture().get(30, TimeUnit.SECONDS);
        ScheduledStart starts = new ScheduledStart(registry, built);
        schedules.onFire(starts);

        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, built), new ResumeReaction(built)))
                .subscribeTo(new SqsDomainEventSource(sqs, domainEventsQueue));
        this.registry = registry;
        this.scheduledStart = starts;
        engine = built;
        LOG.infof("Billing engine started with %d workflow definition(s)", definitions.size());
    }

    /** A worker binding: tasks queue -> worker runtime -> results queue, acked after the result is sent. */
    private void startWorker(BlobStore blobs) {
        BillingHandlers handlers = new BillingHandlers(new S3InvoiceDocuments(s3, factsBucket),
                new DynamoDbInvoiceRecords(ddb, invoicesTable), new DynamoDbEffectMarkers(ddb, effectsTable),
                new QuarkusMailSender(mailer), new FaultInjection(slowDelay), InstantSource.system(), mailFrom, mailTo);
        WorkerRuntime runtime = new WorkerRuntime(registry(FailureLog.wrap(handlers.registry(), List.of(Billing.ROUTE_RENDER_INVOICE, Billing.ROUTE_RECORD_INVOICE, Billing.ROUTE_COUNT_INVOICES, Billing.ROUTE_SEND_SUMMARY)), haltInTask, Chaos.HALT), blobs, tracer);
        worker = SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(tasksQueue)
                .pool(new WorkerPool(runtime, workerConcurrency)).waitTime(Duration.ofSeconds(5))
                .visibilityTimeout(Duration.ofSeconds(30))
                .shipResult(result -> CompletableFuture.runAsync(() -> {
                    sqs.sendMessage(r -> r.queueUrl(resultsQueue)
                            .messageBody(new String(CeWire.toJson(result), StandardCharsets.UTF_8)));
                }))
                .build().start();
        workerRunning = true;
        LOG.infof("Billing worker: SqsWorkerBinding with a WorkerPool of %d", workerConcurrency);
    }

    /** SDD S.4, 8b: the domain's publisher, decorated with the chaos point only when one is configured. */
    static EventPublisher publisher(EventPublisher publisher, Optional<String> haltBeforePublish, Chaos chaos) {
        if (haltBeforePublish.isEmpty()) {
            return publisher;
        }
        LOG.warnf("CHAOS armed: the engine halts on the first publication of %s", haltBeforePublish.get());
        return new ChaosEventPublisher(publisher, haltBeforePublish.get(), chaos);
    }

    /** SDD S.4, 8b: the worker's handlers, one of them decorated with the chaos point only when one is configured. */
    static TaskHandlerRegistry registry(TaskHandlerRegistry registry, Optional<String> haltInTask, Chaos chaos) {
        if (haltInTask.isEmpty()) {
            return registry;
        }
        LOG.warnf("CHAOS armed: the worker halts after the effect of its first %s task", haltInTask.get());
        return ChaosTaskHandler.wrap(registry, haltInTask.get(), chaos);
    }

    /** CA3: a failed result as it goes back to the engine, with its DSL 1.0 error type and status. */
    void onStop(@Observes ShutdownEvent event) {
        workerRunning = false;
        if (worker != null) {
            worker.close();
        }
    }

    /** Whether this process runs the engine and it started. */
    public boolean engineStarted() {
        return engine != null;
    }

    public boolean engineEnabled() {
        return engineEnabled;
    }

    public boolean workerRunning() {
        return workerRunning;
    }

    public ExecutionTracer tracer() {
        return tracer;
    }

    /** The running engine (null until it started, or when this process runs the worker only). */
    public WorkflowEngine engine() {
        return engine;
    }

    public WorkflowRegistry registry() {
        return registry;
    }

    /** The handler schedule occurrences go through: a test can redeliver an occurrence through it (CA5). */
    public ScheduledStart scheduledStart() {
        return scheduledStart;
    }
}
