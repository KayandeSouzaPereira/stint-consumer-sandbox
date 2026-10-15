package io.stintflow.sandbox.business;

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
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.WorkflowRegistry;
import io.stintflow.core.expr.JqExpressionEvaluator;
import io.stintflow.core.interpreter.TreeInterpreter;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.inmemory.InMemoryAuditLog;
import io.stintflow.inmemory.InMemoryCorrectionLog;
import io.stintflow.inmemory.InMemoryEventPublisher;
import io.stintflow.inmemory.InMemoryStateStore;
import io.stintflow.inmemory.InMemoryTaskTransport;
import io.stintflow.inmemory.InMemoryTimerService;
import io.stintflow.spi.BlobStore;
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
    private final WorkerPool pool;

    InMemoryDomain(String domain, String yaml, Function<BlobStore, TaskHandlerRegistry> handlers)
            throws Exception {
        pool = new WorkerPool(new WorkerRuntime(handlers.apply(blobs), blobs, ExecutionTracer.NOOP), 4);
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
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("stint/workflows/" + yaml)) {
            definition = new DslLoader().load(in, yaml, LoadOptions.STRICT);
        }
        registry.register(definition);
        engine = new WorkflowEngine(registry, transport, state, new InMemoryTimerService(), blobs,
                new TreeInterpreter(new JqExpressionEvaluator()), InstantSource.system(),
                new DomainTagPublisher(bus, domain), null, ExecutionTracer.NOOP).auditLog(audit)
                .correctionLog(corrections);
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

    InstanceSnapshot snapshot(String id) throws Exception {
        return state.load(id).toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow();
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
