package io.stintflow.sandbox.delivery;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
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
import io.stintflow.inmemory.InMemoryAuditLog;
import io.stintflow.inmemory.InMemoryCorrectionLog;
import io.stintflow.inmemory.InMemoryDomainEventBus;
import io.stintflow.inmemory.InMemoryEventPublisher;
import io.stintflow.inmemory.InMemoryStateStore;
import io.stintflow.inmemory.InMemoryTaskTransport;
import io.stintflow.inmemory.InMemoryTimerService;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.StateStore;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;

/**
 * Test support (SDD S.5): one domain's workflow on Stint's in-memory engine, with the domain's publisher decorator,
 * the given handlers behind a {@link WorkerPool}, and a {@code reviewer} that answers each review request (or not).
 * No floci, no sleep: the run polls the instance's state with a deadline and parks a few milliseconds.
 */
final class InMemoryDomain implements AutoCloseable {

    final InMemoryStateStore state = new InMemoryStateStore();
    final InMemoryAuditLog audit = new InMemoryAuditLog();
    final InMemoryCorrectionLog corrections = new InMemoryCorrectionLog();
    final InMemoryEventPublisher bus = new InMemoryEventPublisher();
    final MemoryBlobs blobs = new MemoryBlobs();
    final WorkflowEngine engine;
    final WorkflowDefinition definition;
    /** The engine's clock and its timers: a test advances the clock and ticks them (no sleep). */
    final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
            new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-18T12:00:00Z"));
    final InstantSource clock = () -> now.get();
    final InMemoryTimerService timers = new InMemoryTimerService(clock);
    private final WorkerPool pool;

    InMemoryDomain(String domain, String yaml, BiFunction<BlobStore, StateStore, TaskHandlerRegistry> handlers)
            throws Exception {
        this(domain, List.of(yaml), handlers);
    }

    /**
     * Several definitions on one engine, and the bus looped back to it — as {@code sandbox-domain} routes a domain's
     * own events to its queue: a published event starts ({@code schedule.on}) or resumes ({@code listen}) instances.
     */
    InMemoryDomain(String domain, List<String> yamls, BiFunction<BlobStore, StateStore, TaskHandlerRegistry> handlers)
            throws Exception {
        pool = new WorkerPool(new WorkerRuntime(handlers.apply(blobs, state), blobs, ExecutionTracer.NOOP), 4);
        InMemoryTaskTransport transport = new InMemoryTaskTransport();
        transport.connectWorker(invoke -> {
            CompletableFuture<CloudEvent> result = new CompletableFuture<>();
            try {
                pool.submit(invoke, e -> {
                    result.complete(e);
                    return CompletableFuture.completedFuture(null);
                }, () -> { });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(e);
            }
            return result;
        });
        WorkflowRegistry registry = new WorkflowRegistry().requirePolicy();
        WorkflowDefinition first = null;
        for (String yaml : yamls) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("stint/workflows/" + yaml)) {
                WorkflowDefinition loaded = new DslLoader().load(in, yaml, LoadOptions.STRICT);
                registry.register(loaded);
                first = first == null ? loaded : first;
            }
        }
        definition = first;
        engine = new WorkflowEngine(registry, transport, state, timers, blobs,
                new TreeInterpreter(new JqExpressionEvaluator()), clock,
                new DomainTagPublisher(bus, domain), null, ExecutionTracer.NOOP).auditLog(audit)
                .correctionLog(corrections);
        if (yamls.size() > 1) {
            InMemoryDomainEventBus loop = new InMemoryDomainEventBus();
            TriggerBindings bindings = new TriggerBindings(registry);
            bindings.bindDeclaredSchedules();
            new DomainEventRouter(List.of(new StartReaction(bindings, engine), new ResumeReaction(engine)))
                    .subscribeTo(loop);
            bus.connectTo(loop);
        }
    }

    /**
     * Starts from {@code trigger} and runs until the instance ends, the deadline passes, or it waits on a review the
     * reviewer leaves unanswered. {@code reviewer} gets each new review request and returns the answer to deliver
     * (or null).
     */
    InstanceSnapshot run(CloudEvent trigger, Function<CloudEvent, CloudEvent> reviewer, Duration patience)
            throws Exception {
        String id = engine.startFromEvent(definition.ref(), trigger).toCompletableFuture().get(30, TimeUnit.SECONDS);
        return drive(id, reviewer, patience);
    }

    InstanceSnapshot drive(String id, Function<CloudEvent, CloudEvent> reviewer, Duration patience) throws Exception {
        Set<String> seen = new HashSet<>();
        long until = System.nanoTime() + patience.toNanos();
        InstanceSnapshot snapshot = snapshot(id);
        while (!terminal(snapshot) && System.nanoTime() < until) {
            for (CloudEvent event : List.copyOf(bus.published())) {
                if (event.getType().equals("io.stintflow.review.requested.v1") && seen.add(event.getId())) {
                    CloudEvent answer = reviewer.apply(event);
                    if (answer != null) {
                        engine.onDomainEvent(answer, Duration.ofMinutes(5)).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS);
                    }
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            snapshot = snapshot(id);
        }
        return snapshot;
    }

    /** Lets {@code by} pass: the coordinator's wake-up timers (PT5M) come due and fire. */
    void nap(Duration by) {
        now.updateAndGet(t -> t.plus(by));
        timers.tick();
    }

    InstanceSnapshot snapshot(String id) throws Exception {
        return state.load(id).toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow();
    }

    /**
     * The events of {@code type} once there are at least {@code count}: the outbox publishes after the instance's
     * save, so a finished instance's last facts may still be on their way.
     */
    List<CloudEvent> awaitPublished(String type, int count, Duration patience) {
        long until = System.nanoTime() + patience.toNanos();
        List<CloudEvent> found = published(type);
        while (found.size() < count && System.nanoTime() < until) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            found = published(type);
        }
        return found;
    }

    List<CloudEvent> published(String type) {
        List<CloudEvent> out = new ArrayList<>();
        bus.published().stream().filter(e -> e.getType().equals(type)).forEach(out::add);
        return out;
    }

    static boolean terminal(InstanceSnapshot s) {
        return s.status() == InstanceStatus.COMPLETED || s.status() == InstanceStatus.FAILED;
    }

    static JsonNode json(CloudEvent event) {
        return io.stintflow.wire.Json.read(io.stintflow.wire.CeWire.toJson(event));
    }

    static URI uri(String text) {
        return URI.create(text);
    }

    @Override
    public void close() {
        pool.close();
    }
}
