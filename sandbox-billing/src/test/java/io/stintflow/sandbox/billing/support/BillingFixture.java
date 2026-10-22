package io.stintflow.sandbox.billing.support;

import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
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
import io.stintflow.inmemory.FilesystemBlobStore;
import io.stintflow.inmemory.InMemoryDomainEventBus;
import io.stintflow.inmemory.InMemoryEventPublisher;
import io.stintflow.inmemory.InMemoryScheduleService;
import io.stintflow.inmemory.InMemoryStateStore;
import io.stintflow.inmemory.InMemoryTaskTransport;
import io.stintflow.inmemory.InMemoryTimerService;
import io.stintflow.sandbox.billing.Billing;
import io.stintflow.sandbox.billing.worker.BillingHandlers;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceDocuments;
import io.stintflow.sandbox.billing.worker.FaultInjection;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.ScheduleSpec;
import io.stintflow.spi.TimerFireHandler;
import io.stintflow.spi.TimerRequest;
import io.stintflow.spi.TimerService;
import io.stintflow.wire.Json;
import io.stintflow.worker.WorkerRuntime;

/**
 * SDD S.2, CA8: Billing's two definitions — loaded from the module's own resources, as in production — on an
 * engine over Stint's in-memory connectors (test scope only), with the real handlers over in-memory ports.
 * Inbound events go through the same {@link DomainEventRouter} + {@link StartReaction} as on floci; the clock only
 * moves when a test moves it, and every wait is on an observed event (no sleeps).
 */
public final class BillingFixture {

    public static final Instant START = Instant.parse("2026-10-10T12:00:30Z");
    public static final URI REQUEST_SOURCE = URI.create("https://sandbox.stintflow.io/orders");

    public final MutableClock clock = new MutableClock(START);
    public final InMemoryBillingPorts.Records records = new InMemoryBillingPorts.Records();
    public final InMemoryBillingPorts.Markers markers = new InMemoryBillingPorts.Markers();
    public final InMemoryBillingPorts.CapturingMail mail = new InMemoryBillingPorts.CapturingMail();
    public final InMemoryBillingPorts.Documents documents = new InMemoryBillingPorts.Documents();
    public final ObservedStateStore state = new ObservedStateStore(new InMemoryStateStore());
    public final CapturingTimers timers = new CapturingTimers(new InMemoryTimerService(clock));
    /** Every task invocation and every result, as they travel between engine and worker. */
    public final List<CloudEvent> invokes = new CopyOnWriteArrayList<>();
    public final List<CloudEvent> results = new CopyOnWriteArrayList<>();
    /** The facts the engine published, in order. */
    public final BlockingQueue<CloudEvent> facts = new LinkedBlockingQueue<>();
    public final List<CloudEvent> allFacts = new CopyOnWriteArrayList<>();
    public final InMemoryDomainEventBus bus = new InMemoryDomainEventBus();
    public final InMemoryScheduleService schedules = new InMemoryScheduleService(clock);
    public final WorkflowRegistry registry = new WorkflowRegistry();
    public final WorkflowEngine engine;
    private final WorkerRuntime worker;

    public BillingFixture() throws Exception {
        this(null);
    }

    /** @param renderStore the renderer's document store, or {@code null} for {@link #documents} */
    public BillingFixture(InvoiceDocuments renderStore) throws Exception {
        new DslLoader().loadClasspath("stint/workflows/*.yaml", LoadOptions.STRICT).forEach(registry::register);

        BlobStore blobs = new FilesystemBlobStore(Files.createTempDirectory("sandbox-billing-test"));
        BillingHandlers handlers = new BillingHandlers(renderStore != null ? renderStore : documents, records,
                markers, mail, new FaultInjection(Duration.ZERO), clock, "billing@sandbox.local",
                "finance@sandbox.local");
        worker = new WorkerRuntime(handlers.registry(), blobs);
        InMemoryTaskTransport transport = new InMemoryTaskTransport();
        transport.connectWorker(invoke -> {
            invokes.add(invoke);
            return worker.handle(invoke).thenApply(result -> {
                results.add(result);
                return result;
            });
        });

        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        publisher.subscribe(fact -> {
            allFacts.add(fact);
            facts.add(fact);
            return CompletableFuture.completedFuture(null);
        });
        engine = new WorkflowEngine(registry, transport, state, timers, blobs,
                new TreeInterpreter(new JqExpressionEvaluator()), clock, publisher, blobs);

        schedules.onFire(new ScheduledStart(registry, engine));
        new ScheduleReconciler(registry, schedules).reconcile().toCompletableFuture().get(10, TimeUnit.SECONDS);
        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, engine), new ResumeReaction(engine)))
                .subscribeTo(bus);
    }

    /** A contract invoice-requested.v1 with an explicit chain. */
    public static CloudEvent request(String eventId, String chainId, JsonNode data) {
        return CloudEventBuilder.v1()
                .withId(eventId)
                .withSource(REQUEST_SOURCE)
                .withType(Billing.INVOICE_REQUESTED)
                .withDataContentType("application/json")
                .withData(Json.bytes(data))
                .withExtension("chainid", chainId)
                .build();
    }

    /** Delivers an inbound event as the domain-events queue would, and waits until it was handled. */
    public void deliver(CloudEvent event) throws Exception {
        bus.publish(event).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    public String issueInvoiceInstance(CloudEvent request) {
        return WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, request);
    }

    public JsonNode awaitCompleted(String instanceId) throws Exception {
        return engine.awaitCompletion(instanceId, Duration.ofSeconds(10)).toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
    }

    public InstanceSnapshot load(String instanceId) throws Exception {
        return state.load(instanceId).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
    }

    /** The next published fact (waits for it). */
    public CloudEvent nextFact() throws InterruptedException {
        CloudEvent fact = facts.poll(10, TimeUnit.SECONDS);
        if (fact == null) {
            throw new AssertionError("no fact was published within 10 s");
        }
        return fact;
    }

    /**
     * Waits until the instance is saved waiting for a retry delay, then moves the clock to the delay's end and
     * fires it. Returns the snapshot that waited (its retry state holds the error that was caught).
     */
    public InstanceSnapshot fireNextRetry() throws Exception {
        InstanceSnapshot waiting = state.retryWaits().poll(10, TimeUnit.SECONDS);
        if (waiting == null) {
            throw new AssertionError("no retry was scheduled within 10 s");
        }
        TimerRequest retry = timers.requests.get(waiting.waitingKey());
        clock.set(retry.fireAt());
        timers.delegate.tick();
        return waiting;
    }

    public ScheduleSpec summarySpec() {
        return registry.scheduleSpecs().stream()
                .filter(spec -> spec.definition().equals(Billing.BILLING_SUMMARY))
                .findFirst().orElseThrow();
    }

    public String summaryInstance(Instant occurrence) {
        return WorkflowEngine.triggeredInstanceId(Billing.BILLING_SUMMARY,
                ScheduledStart.syntheticEvent(summarySpec(), occurrence));
    }

    /** Hands an invocation to the worker again, as an SQS redelivery would; returns the result's data. */
    public JsonNode replayToWorker(CloudEvent invoke) throws Exception {
        CloudEvent result = worker.handle(invoke).toCompletableFuture().get(10, TimeUnit.SECONDS);
        return Json.read(result.getData().toBytes());
    }

    /** The invocations of one route, in order. */
    public List<CloudEvent> invokesOf(String route) {
        return invokes.stream().filter(invoke -> route.equals(invoke.getSubject())).toList();
    }

    /** Remembers every armed timer, so a test can fire a retry delay exactly when it ends. */
    public static final class CapturingTimers implements TimerService {
        final InMemoryTimerService delegate;
        final Map<String, TimerRequest> requests = new ConcurrentHashMap<>();

        CapturingTimers(InMemoryTimerService delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<String> schedule(TimerRequest req) {
            requests.put(req.timerId(), req);
            return delegate.schedule(req);
        }

        @Override
        public CompletionStage<Void> cancel(String timerId) {
            return delegate.cancel(timerId);
        }

        @Override
        public void onFire(TimerFireHandler handler) {
            delegate.onFire(handler);
        }

        @Override
        public Duration maxDelay() {
            return delegate.maxDelay();
        }
    }
}
