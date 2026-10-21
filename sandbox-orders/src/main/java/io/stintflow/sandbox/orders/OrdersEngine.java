package io.stintflow.sandbox.orders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
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
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.sandbox.orders.aws.DynamoDbEffectMarkers;
import io.stintflow.sandbox.orders.aws.QuarkusMailSender;
import io.stintflow.sandbox.orders.aws.S3InvoiceDocuments;
import io.stintflow.sandbox.orders.worker.FailureLog;
import io.stintflow.sandbox.orders.worker.OrdersHandlers;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.spi.StateStore;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
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
 * SDD S.1: the Orders domain's own engine, over Stint's AWS connectors only (no stint-inmemory). It is built like
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
 * SDD S.3: the engine runs {@code place-order} (its listen timeouts from {@code orders.invoice.timeout} and
 * {@code orders.late-invoice.timeout}, {@link OrderDefinitions}); the worker runs the three e-mails
 * ({@link OrdersHandlers}) and logs every failed result as it travels back. The state store is built in either
 * role, so {@code GET /orders/{orderId}} can read the order's snapshot.
 * The connectors are built by hand, so nothing here reads Stint's global {@code stint.aws.*} properties: two
 * domains can never share a resource by accident. {@code orders.engine.enabled} / {@code orders.worker.enabled}
 * let the same jar run either role alone (SDD S.1, sec. 8d).
 */
@ApplicationScoped
public class OrdersEngine {

    private static final Logger LOG = Logger.getLogger(OrdersEngine.class);

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

    @ConfigProperty(name = "orders.sqs.tasks-queue-url")
    String tasksQueue;
    @ConfigProperty(name = "orders.sqs.results-queue-url")
    String resultsQueue;
    @ConfigProperty(name = "orders.sqs.timers-queue-url")
    String timersQueue;
    @ConfigProperty(name = "orders.sqs.schedules-queue-url")
    String schedulesQueue;
    @ConfigProperty(name = "orders.sqs.domain-events-queue-url")
    String domainEventsQueue;
    @ConfigProperty(name = "orders.sqs.timers-queue-arn")
    String timersQueueArn;
    @ConfigProperty(name = "orders.sqs.schedules-queue-arn")
    String schedulesQueueArn;
    @ConfigProperty(name = "orders.dynamodb.instances-table")
    String instancesTable;
    @ConfigProperty(name = "orders.dynamodb.waits-table")
    String waitsTable;
    @ConfigProperty(name = "orders.dynamodb.outbox-table")
    String outboxTable;
    @ConfigProperty(name = "orders.dynamodb.inbox-table")
    String inboxTable;
    @ConfigProperty(name = "orders.s3.blobs-bucket")
    String blobsBucket;
    @ConfigProperty(name = "orders.s3.facts-bucket")
    String factsBucket;
    @ConfigProperty(name = "orders.eventbridge.domain-bus")
    String domainBus;
    @ConfigProperty(name = "orders.scheduler.schedules-group")
    String schedulesGroup;
    @ConfigProperty(name = "orders.scheduler.timers-group")
    String timersGroup;
    @ConfigProperty(name = "orders.scheduler.role-arn")
    String roleArn;
    @ConfigProperty(name = "orders.scheduler.sent-timestamp-fallback", defaultValue = "false")
    boolean sentTimestampFallback;
    @ConfigProperty(name = "orders.dynamodb.effects-table")
    String effectsTable;
    @ConfigProperty(name = "orders.mail.from")
    String mailFrom;
    @ConfigProperty(name = "orders.invoice.timeout", defaultValue = "PT2M")
    Duration invoiceTimeout;
    @ConfigProperty(name = "orders.late-invoice.timeout", defaultValue = "PT24H")
    Duration lateInvoiceTimeout;
    @ConfigProperty(name = "orders.engine.enabled", defaultValue = "true")
    boolean engineEnabled;
    @ConfigProperty(name = "orders.worker.enabled", defaultValue = "true")
    boolean workerEnabled;

    private volatile WorkflowEngine engine;
    private volatile StateStore state;
    private volatile WorkflowRegistry registry;
    private volatile ExecutionTracer tracer = ExecutionTracer.NOOP;
    private volatile boolean workerRunning;
    /** SDD S.5, RF9: Stint's SQS worker binding (3a.7) with a WorkerPool, in place of the serial loop of S.1-S.4. */
    private volatile SqsWorkerBinding worker;
    @ConfigProperty(name = "orders.worker.concurrency", defaultValue = "8")
    int workerConcurrency;

    void onStart(@Observes StartupEvent event) throws Exception {
        tracer = new OpenTelemetryExecutionTracer(openTelemetry);
        BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        state = new DynamoDbStateStore(ddb, instancesTable, waitsTable, outboxTable, 4, inboxTable);
        if (engineEnabled) {
            startEngine(blobs);
        }
        if (workerEnabled) {
            startWorker(blobs);
        }
        LOG.infof("Orders domain started: engine=%s worker=%s, over %s, %s, %s, %s, %s", engineEnabled, workerEnabled,
                DynamoDbStateStore.class.getSimpleName(), SqsTaskTransport.class.getSimpleName(),
                DelayRoutingTimerService.class.getSimpleName(), EventBridgeEventPublisher.class.getSimpleName(),
                SqsDomainEventSource.class.getSimpleName());
    }

    private void startEngine(BlobStore blobs) throws Exception {
        DelayRoutingTimerService timers = new DelayRoutingTimerService(new SqsDelayTimerService(sqs, timersQueue),
                new EventBridgeSchedulerTimerService(scheduler, timersGroup, roleArn, timersQueueArn,
                        Duration.ofDays(365)), InstantSource.system());
        WorkflowRegistry registry = new WorkflowRegistry(timers.maxDelay());
        List<WorkflowDefinition> definitions = List.of(OrderDefinitions.placeOrder(invoiceTimeout, lateInvoiceTimeout));
        definitions.forEach(registry::register);

        WorkflowEngine built = new WorkflowEngine(registry, new SqsTaskTransport(sqs, tasksQueue, resultsQueue),
                state,
                timers, blobs, new TreeInterpreter(new JqExpressionEvaluator()), InstantSource.system(),
                new EventBridgeEventPublisher(eventBridge, domainBus, "default"), new S3FactBlobStore(s3, factsBucket),
                tracer);
        built.outboxRelay().start(Duration.ofSeconds(30));

        EventBridgeSchedulerService schedules = new EventBridgeSchedulerService(scheduler, sqs, schedulesGroup, roleArn,
                schedulesQueueArn, schedulesQueue, sentTimestampFallback);
        new ScheduleReconciler(registry, schedules).reconcile().toCompletableFuture().get(30, TimeUnit.SECONDS);
        schedules.onFire(new ScheduledStart(registry, built));

        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, built), new ResumeReaction(built)))
                .subscribeTo(new SqsDomainEventSource(sqs, domainEventsQueue));
        this.registry = registry;
        engine = built;
        LOG.infof("Orders engine started with %d workflow definition(s); invoice timeout %s, late invoice timeout %s",
                definitions.size(), invoiceTimeout, lateInvoiceTimeout);
    }

    /** A worker binding: tasks queue -> worker runtime -> results queue, acked after the result is sent. */
    private void startWorker(BlobStore blobs) {
        OrdersHandlers handlers = new OrdersHandlers(new S3InvoiceDocuments(s3), new DynamoDbEffectMarkers(ddb, effectsTable),
                new QuarkusMailSender(mailer), InstantSource.system(), mailFrom, factsBucket);
        WorkerRuntime runtime = new WorkerRuntime(FailureLog.wrap(handlers.registry(), List.of(Orders.ROUTE_SEND_CONFIRMATION, Orders.ROUTE_SEND_REJECTION, Orders.ROUTE_SEND_DELAY_NOTICE)), blobs, tracer);
        worker = SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(tasksQueue)
                .pool(new WorkerPool(runtime, workerConcurrency)).waitTime(Duration.ofSeconds(5))
                .visibilityTimeout(Duration.ofSeconds(30))
                .shipResult(result -> CompletableFuture.runAsync(() -> {
                    sqs.sendMessage(r -> r.queueUrl(resultsQueue)
                            .messageBody(new String(CeWire.toJson(result), StandardCharsets.UTF_8)));
                }))
                .build().start();
        workerRunning = true;
        LOG.infof("Orders worker: SqsWorkerBinding with a WorkerPool of %d", workerConcurrency);
    }

    /** A failed result as it goes back to the engine, with its DSL 1.0 error type and status. */
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

    /** Orders' state store, in either role: GET /orders/{orderId} reads the order's snapshot from it (S.3 8d). */
    public StateStore state() {
        return state;
    }

    public WorkflowRegistry registry() {
        return registry;
    }
}
