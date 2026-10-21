package io.stintflow.sandbox.engineering;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.DynamoDbAuditLog;
import io.stintflow.aws.DynamoDbCorrectionLog;
import io.stintflow.aws.DynamoDbStateStore;
import io.stintflow.aws.EventBridgeEventPublisher;
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
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import io.stintflow.worker.decision.DecisionConfig;
import io.stintflow.worker.decision.DecisionHandlers;
import io.stintflow.worker.decision.SensitivityPolicy;
import io.stintflow.worker.transcription.TranscriptionHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.5, 8c: the Engineering domain's engine and worker, over Stint's AWS connectors (as the Phase 2 domains):
 * <ul>
 *   <li>state, waits, outbox and inbox in {@code engineering-*} tables; the review audit in {@code engineering-audit}
 *       and the corrections in {@code engineering-corrections} (Stint 3a.4/3a.5);</li>
 *   <li>tasks over a {@link RoutingTaskTransport}: the shared services' routes to their queue for this domain, the
 *       rest to {@code business-tasks}, results on {@code business-results};</li>
 *   <li>facts on {@code sandbox-domain} through the outbox, review events tagged with the domain
 *       ({@link DomainTagPublisher});</li>
 *   <li>inbound events from {@code engineering-domain-events}: started ({@code schedule.on}) or resumed (reviews);</li>
 *   <li>the worker ({@code transcribe}, the decisions, {@code write-sdds}, {@code write-questions}) behind Stint's {@code SqsWorkerBinding}
 *       with a {@code WorkerPool} (3a.7).</li>
 * </ul>
 * CI models only (the local ones are S.9). {@code engineering.engine.enabled} and {@code engineering.worker.enabled}
 * let one jar run either role.
 */
@ApplicationScoped
public class EngineeringEngine {

    private static final Logger LOG = Logger.getLogger(EngineeringEngine.class);
    public static final String DOMAIN = "engineering";
    /** Routing key -> shared service (pipeline-events.md, "Tarefas remotas compartilhadas"). */
    static final Map<String, String> SHARED = Map.of("redactor", "privacy", "register-document", "records", "approve-record", "records", "read-record", "records",
            "send-email", "channels");

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
    @ConfigProperty(name = "engineering.engine.enabled", defaultValue = "true")
    boolean engineEnabled;
    @ConfigProperty(name = "engineering.worker.enabled", defaultValue = "true")
    boolean workerEnabled;
    @ConfigProperty(name = "engineering.worker.concurrency", defaultValue = "4")
    int concurrency;
    @ConfigProperty(name = "engineering.domain-bus")
    String domainBus;
    @ConfigProperty(name = "engineering.blobs-bucket")
    String blobsBucket;
    @ConfigProperty(name = "engineering.facts-bucket")
    String factsBucket;
    @ConfigProperty(name = "engineering.raw-bucket")
    String rawBucket;
    @ConfigProperty(name = "engineering.documents-bucket")
    String documentsBucket;
    @ConfigProperty(name = "engineering.timers-group")
    String timersGroup;
    @ConfigProperty(name = "engineering.scheduler.role-arn")
    String roleArn;

    private volatile WorkflowEngine engine;
    private volatile SqsWorkerBinding worker;
    private volatile ExecutionTracer tracer = ExecutionTracer.NOOP;

    void onStart(@Observes StartupEvent event) throws Exception {
        tracer = new OpenTelemetryExecutionTracer(openTelemetry);
        BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        if (engineEnabled) {
            startEngine(blobs);
        }
        if (workerEnabled) {
            startWorker(blobs);
        }
        LOG.infof("Engineering domain started: engine=%s worker=%s", engineEnabled, workerEnabled);
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
                new DynamoDbStateStore(ddb, DOMAIN + "-instances", DOMAIN + "-waits", DOMAIN + "-outbox", 4,
                        DOMAIN + "-inbox"),
                timers, blobs, new TreeInterpreter(new JqExpressionEvaluator()), InstantSource.system(),
                new DomainTagPublisher(new EventBridgeEventPublisher(eventBridge, domainBus, "default"), DOMAIN),
                new S3FactBlobStore(s3, factsBucket), tracer)
                .auditLog(new DynamoDbAuditLog(ddb, DOMAIN + "-audit", DynamoDbAuditLog.DEFAULT_TTL,
                        InstantSource.system()))
                .correctionLog(new DynamoDbCorrectionLog(ddb, DOMAIN + "-corrections", Duration.ofDays(400),
                        InstantSource.system()));
        built.outboxRelay().start(Duration.ofSeconds(30));
        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, built), new ResumeReaction(built)))
                .subscribeTo(new SqsDomainEventSource(sqs, queueBase + DOMAIN + "-domain-events"));
        engine = built;
        LOG.infof("Engineering engine started with %d workflow definition(s)", definitions.size());
    }

    private void startWorker(BlobStore blobs) {
        Corpus corpus = Corpus.load(Path.of(corpusDir));
        worker = SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + DOMAIN + "-tasks")
                .resultQueueUrl(queueBase + DOMAIN + "-results")
                .pool(new WorkerPool(new WorkerRuntime(handlers(corpus, EngineeringModels.fakes(corpus),
                        new S3BlobStore(s3, rawBucket), new S3BlobStore(s3, documentsBucket)), blobs, tracer),
                        concurrency))
                .waitTime(Duration.ofSeconds(5)).build().start();
    }

    /** The domain's own routes: transcription, the four decisions and the two writers. */
    public static TaskHandlerRegistry handlers(Corpus corpus, EngineeringModels stack, BlobStore raw,
            BlobStore documents) {
        Map<String, String> config = new HashMap<>();
        stack.adapterOf().forEach((route, adapter) -> config.put("stint.decision.routes." + route + ".adapter",
                adapter));
        config.put("stint.decision.routes.decide-meeting-type.instructions",
                "Is this meeting a business design meeting, a technical refinement, or out of scope?");
        config.put("stint.decision.routes.decide-priority.instructions",
                "How urgent is the work this meeting refines, from 0 (not at all) to 1 (this week)?");
        config.put("stint.decision.routes.decide-outcome.instructions",
                "Did the meeting decide the work (write the SDDs), or does it need answers from Business first?");
        config.put("stint.decision.routes.decide-blocking.instructions",
                "Is there a question Business must answer before any SDD can be written?");
        DecisionHandlers.Builder decisions = DecisionHandlers.builder(DecisionConfig.fromProperties(config))
                .policy(SensitivityPolicy.fromProperties(Map.of()));
        stack.decisions().forEach(decisions::adapter);
        return decisions.build().registerAll(new TaskHandlerRegistry())
                .register("transcribe", new TranscriptionHandler(stack.transcriber(), raw))
                .register("write-sdds", EngineeringWriters.sdds(corpus, documents))
                .register("finalize-batch", EngineeringWriters.finalizeBatch(corpus, documents))
                .register(TimeoutReportHandler.ROUTE, new TimeoutReportHandler(documents))
                .register("finalize-batch", EngineeringWriters.finalizeBatch(corpus, documents))
                .register(TimeoutReportHandler.ROUTE, new TimeoutReportHandler(documents))
                .register("write-questions", EngineeringWriters.questions(corpus, documents));
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
