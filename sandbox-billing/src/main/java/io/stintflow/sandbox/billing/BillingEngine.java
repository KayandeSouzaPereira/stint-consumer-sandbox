package io.stintflow.sandbox.billing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import io.stintflow.sandbox.billing.worker.BillingHandlers;
import io.stintflow.sandbox.billing.worker.FaultInjection;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import io.stintflow.worker.WorkerRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * SDD S.1: the Billing domain's own engine, over Stint's AWS connectors only (no stint-inmemory). It is built like
 * Stint's {@code stint-example-aws-smoke} (SDD 2.7), from the clients the Quarkus extensions produce:
 * <ul>
 *   <li>state, waits, outbox and inbox in the domain's DynamoDB tables;</li>
 *   <li>tasks over the domain's SQS queues, with an in-process worker loop;</li>
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

    private volatile WorkflowEngine engine;
    private volatile WorkflowRegistry registry;
    private volatile ScheduledStart scheduledStart;
    private volatile ExecutionTracer tracer = ExecutionTracer.NOOP;
    private volatile boolean workerRunning;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "billing-worker");
        t.setDaemon(true);
        return t;
    });

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
                new EventBridgeEventPublisher(eventBridge, domainBus, "default"), new S3FactBlobStore(s3, factsBucket),
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
        WorkerRuntime runtime = new WorkerRuntime(handlers.registry(), blobs, tracer);
        workerRunning = true;
        worker.submit(() -> {
            while (workerRunning) {
                try {
                    for (Message m : sqs.receiveMessage(r -> r.queueUrl(tasksQueue).waitTimeSeconds(5)).messages()) {
                        CloudEvent invoke = CeWire.fromJson(m.body().getBytes(StandardCharsets.UTF_8));
                        CloudEvent result = runtime.handle(invoke).toCompletableFuture().get(60, TimeUnit.SECONDS);
                        logIfFailed(invoke, result);
                        sqs.sendMessage(r -> r.queueUrl(resultsQueue)
                                .messageBody(new String(CeWire.toJson(result), StandardCharsets.UTF_8)));
                        sqs.deleteMessage(r -> r.queueUrl(tasksQueue).receiptHandle(m.receiptHandle()));
                    }
                } catch (Exception e) {
                    if (workerRunning) {
                        LOG.warn("Billing worker poll failed", e);
                    }
                }
            }
        });
    }

    /** CA3: a failed result as it goes back to the engine, with its DSL 1.0 error type and status. */
    private static void logIfFailed(CloudEvent invoke, CloudEvent result) {
        JsonNode data = result.getData() == null ? null : Json.read(result.getData().toBytes());
        if (data == null || !"FAILED".equals(data.path("status").asText())) {
            return;
        }
        JsonNode error = data.path("error");
        LOG.warnf("Task %s attempt %s of instance %s failed: type=%s status=%s detail=%s", invoke.getSubject(),
                invoke.getExtension(StintEvents.EXT_ATTEMPT), invoke.getExtension(StintEvents.EXT_WORKFLOW_INSTANCE_ID),
                error.path("type").asText(), error.path("status").asText(), error.path("detail").asText());
    }

    void onStop(@Observes ShutdownEvent event) {
        workerRunning = false;
        worker.shutdownNow();
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
