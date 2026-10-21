package io.stintflow.sandbox.delivery;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.DynamoDbAuditLog;
import io.stintflow.aws.DynamoDbCorrectionLog;
import io.stintflow.aws.DynamoDbStateStore;
import io.stintflow.aws.EventBridgeEventPublisher;
import io.stintflow.aws.EventBridgeSchedulerService;
import io.stintflow.aws.EventBridgeSchedulerTimerService;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.aws.S3FactBlobStore;
import io.stintflow.aws.SqsDelayTimerService;
import io.stintflow.aws.SqsDomainEventSource;
import io.stintflow.aws.worker.SqsWorkerBinding;
import io.stintflow.core.DelayRoutingTimerService;
import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.WorkflowRegistry;
import io.stintflow.core.expr.JqExpressionEvaluator;
import io.stintflow.core.schedule.ScheduleReconciler;
import io.stintflow.core.schedule.ScheduledStart;
import io.stintflow.core.interpreter.TreeInterpreter;
import io.stintflow.core.trigger.DomainEventRouter;
import io.stintflow.core.trigger.ResumeReaction;
import io.stintflow.core.trigger.StartReaction;
import io.stintflow.core.trigger.TriggerBindings;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.spi.StateStore;
import io.stintflow.worker.TaskHandlerRegistry;
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
 * SDD S.5, 8c: the Delivery domain's engine and worker, over Stint's AWS connectors (as the Phase 2 domains):
 * <ul>
 *   <li>state, waits, outbox and inbox in {@code delivery-*} tables; the review audit in {@code delivery-audit}
 *       and the corrections in {@code delivery-corrections} (Stint 3a.4/3a.5);</li>
 *   <li>tasks over a {@link RoutingTaskTransport}: the shared services' routes to their queue for this domain, the
 *       rest to {@code business-tasks}, results on {@code business-results};</li>
 *   <li>facts on {@code sandbox-domain} through the outbox, review events tagged with the domain
 *       ({@link DomainTagPublisher});</li>
 *   <li>inbound events from {@code delivery-domain-events}: started ({@code schedule.on}) or resumed (reviews);</li>
 *   <li>the worker ({@code load-batch}, {@code implement}, {@code validate}) behind Stint's {@code SqsWorkerBinding}
 *       with a {@code WorkerPool} (3a.7).</li>
 * </ul>
 * CI agents only (the real ones are S.10). {@code delivery.engine.enabled} and {@code delivery.worker.enabled}
 * let one jar run either role.
 */
@ApplicationScoped
public class DeliveryEngine {

    private static final Logger LOG = Logger.getLogger(DeliveryEngine.class);
    public static final String DOMAIN = "delivery";
    /** Routing key -> shared service (pipeline-events.md, "Tarefas remotas compartilhadas"). */
    static final Map<String, String> SHARED = Map.of("run-target-tests", "target", "approve-record", "records", "read-record", "records",
            "register-document", "records", "send-email", "channels");

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

    @ConfigProperty(name = "sandbox.sqs.base-url")
    String queueBase;
    @ConfigProperty(name = "sandbox.corpus-dir")
    String corpusDir;
    @ConfigProperty(name = "delivery.engine.enabled", defaultValue = "true")
    boolean engineEnabled;
    @ConfigProperty(name = "delivery.worker.enabled", defaultValue = "true")
    boolean workerEnabled;
    @ConfigProperty(name = "delivery.worker.concurrency", defaultValue = "4")
    int concurrency;
    @ConfigProperty(name = "delivery.domain-bus")
    String domainBus;
    @ConfigProperty(name = "delivery.blobs-bucket")
    String blobsBucket;
    @ConfigProperty(name = "delivery.facts-bucket")
    String factsBucket;
    @ConfigProperty(name = "delivery.documents-bucket")
    String documentsBucket;
    @ConfigProperty(name = "delivery.timers-group")
    String timersGroup;
    @ConfigProperty(name = "delivery.scheduler.role-arn")
    String roleArn;
    @ConfigProperty(name = "delivery.scheduler.schedules-group", defaultValue = "delivery-schedules")
    String schedulesGroup;
    @ConfigProperty(name = "delivery.scheduler.sent-timestamp-fallback", defaultValue = "false")
    boolean sentTimestampFallback;
    @ConfigProperty(name = "delivery.parked-table", defaultValue = "delivery-parked")
    String parkedTable;

    /** The Delivery's state: the engine's, and what check-sdds reads (in either role). */
    private volatile StateStore state;
    private volatile WorkflowEngine engine;
    private volatile SqsWorkerBinding worker;
    private volatile ExecutionTracer tracer = ExecutionTracer.NOOP;

    void onStart(@Observes StartupEvent event) throws Exception {
        tracer = new OpenTelemetryExecutionTracer(openTelemetry);
        BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        state = new DynamoDbStateStore(ddb, DOMAIN + "-instances", DOMAIN + "-waits", DOMAIN + "-outbox", 4,
                DOMAIN + "-inbox");
        if (engineEnabled) {
            startEngine(blobs);
        }
        if (workerEnabled) {
            startWorker(blobs);
        }
        LOG.infof("Delivery domain started: engine=%s worker=%s", engineEnabled, workerEnabled);
    }

    private void startEngine(BlobStore blobs) {
        String timersQueue = queueBase + DOMAIN + "-timers";
        DelayRoutingTimerService timers = new DelayRoutingTimerService(new SqsDelayTimerService(sqs, timersQueue),
                new EventBridgeSchedulerTimerService(scheduler, timersGroup, roleArn,
                        "arn:aws:sqs:us-east-1:000000000000:" + DOMAIN + "-timers", Duration.ofDays(365)),
                InstantSource.system());
        WorkflowRegistry registry = new WorkflowRegistry(timers.maxDelay()).requirePolicy();
        List<WorkflowDefinition> definitions = new DslLoader().loadClasspath("stint/workflows/*.yaml",
                LoadOptions.STRICT);
        definitions.forEach(registry::register);
        WorkflowEngine built = new WorkflowEngine(registry, new RoutingTaskTransport(sqs, queueBase, DOMAIN, SHARED),
                state,
                timers, blobs, new TreeInterpreter(new JqExpressionEvaluator()), InstantSource.system(),
                new DomainTagPublisher(new EventBridgeEventPublisher(eventBridge, domainBus, "default"), DOMAIN),
                new S3FactBlobStore(s3, factsBucket), tracer)
                .auditLog(new DynamoDbAuditLog(ddb, DOMAIN + "-audit", DynamoDbAuditLog.DEFAULT_TTL,
                        InstantSource.system()))
                .correctionLog(new DynamoDbCorrectionLog(ddb, DOMAIN + "-corrections", Duration.ofDays(400),
                        InstantSource.system()));
        built.outboxRelay().start(Duration.ofSeconds(30));
        // SDD S.10, RF5: the daily DLQ summary is a cron schedule: reconciled into EventBridge Scheduler (its
        // occurrences come back on delivery-schedules), as the Billing domain does.
        EventBridgeSchedulerService schedules = new EventBridgeSchedulerService(scheduler, sqs, schedulesGroup,
                roleArn, "arn:aws:sqs:us-east-1:000000000000:" + DOMAIN + "-schedules",
                queueBase + DOMAIN + "-schedules", sentTimestampFallback);
        try {
            new ScheduleReconciler(registry, schedules).reconcile().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the Delivery's schedules could not be reconciled", e);
        }
        schedules.onFire(new ScheduledStart(registry, built));
        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, built), new ResumeReaction(built)))
                .subscribeTo(new SqsDomainEventSource(sqs, queueBase + DOMAIN + "-domain-events"));
        engine = built;
        LOG.infof("Delivery engine started with %d workflow definition(s)", definitions.size());
    }

    private void startWorker(BlobStore blobs) {
        Corpus corpus = Corpus.load(Path.of(corpusDir));
        worker = SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + DOMAIN + "-tasks")
                .resultQueueUrl(queueBase + DOMAIN + "-results")
                .pool(new WorkerPool(new WorkerRuntime(DeliveryHandlers.register(new TaskHandlerRegistry(), corpus,
                        new S3BlobStore(s3, documentsBucket), state, new DynamoDbParked(ddb, parkedTable)), blobs, tracer), concurrency))
                .waitTime(Duration.ofSeconds(5)).build().start();
    }

    void onStop(@Observes ShutdownEvent event) {
        if (worker != null) {
            worker.close();
        }
    }

    public WorkflowEngine engine() {
        return engine;
    }

    public boolean ready() {
        return (!engineEnabled || engine != null) && (!workerEnabled || worker != null);
    }
}
