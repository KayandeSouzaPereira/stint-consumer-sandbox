package io.stintflow.sandbox.orders.support;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.WorkflowRegistry;
import io.stintflow.core.expr.JqExpressionEvaluator;
import io.stintflow.core.interpreter.TreeInterpreter;
import io.stintflow.core.trigger.DomainEventRouter;
import io.stintflow.core.trigger.ResumeReaction;
import io.stintflow.core.trigger.StartReaction;
import io.stintflow.core.trigger.TriggerBindings;
import io.stintflow.inmemory.FilesystemBlobStore;
import io.stintflow.inmemory.InMemoryDomainEventBus;
import io.stintflow.inmemory.InMemoryEventPublisher;
import io.stintflow.inmemory.InMemoryStateStore;
import io.stintflow.inmemory.InMemoryTaskTransport;
import io.stintflow.inmemory.InMemoryTimerService;
import io.stintflow.sandbox.orders.OrderDefinitions;
import io.stintflow.sandbox.orders.Orders;
import io.stintflow.sandbox.orders.worker.OrdersHandlers;
import io.stintflow.sandbox.orders.worker.OrdersPorts.Attachment;
import io.stintflow.sandbox.orders.worker.OrdersPorts.EffectMarkers;
import io.stintflow.sandbox.orders.worker.OrdersPorts.InvoiceDocuments;
import io.stintflow.sandbox.orders.worker.OrdersPorts.MailSender;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.TimerFireHandler;
import io.stintflow.spi.TimerRequest;
import io.stintflow.spi.TimerService;
import io.stintflow.wire.Json;
import io.stintflow.worker.WorkerRuntime;

/**
 * SDD S.3, CA8: {@code place-order} — loaded as production loads it ({@link OrderDefinitions}, with its timeouts) —
 * on an engine over Stint's in-memory connectors (test scope only), with the real handlers over in-memory ports.
 * The other domain is played by the test, which publishes the contract's invoice events on the bus; nothing here
 * knows how they are produced. The clock moves only when a test moves it; every wait is on an observed event.
 */
public final class OrdersFixture {

    public static final Instant START = Instant.parse("2026-10-10T12:00:00Z");
    public static final Duration T1 = Duration.ofMinutes(2);
    public static final Duration T2 = Duration.ofHours(24);
    public static final URI ISSUER = URI.create("https://sandbox.stintflow.io/billing");

    public final MutableClock clock = new MutableClock(START);
    public final ObservedStateStore state = new ObservedStateStore(new InMemoryStateStore());
    public final CapturingTimers timers = new CapturingTimers(new InMemoryTimerService(clock));
    public final Markers markers = new Markers();
    public final CapturingMail mail = new CapturingMail();
    public final Documents documents = new Documents();
    public final List<CloudEvent> invokes = new CopyOnWriteArrayList<>();
    /** The facts the engine published, in order. */
    public final BlockingQueue<CloudEvent> facts = new LinkedBlockingQueue<>();
    public final List<CloudEvent> allFacts = new CopyOnWriteArrayList<>();
    public final InMemoryDomainEventBus bus = new InMemoryDomainEventBus();
    public final WorkflowRegistry registry = new WorkflowRegistry();
    public final WorkflowEngine engine;
    private final WorkerRuntime worker;
    /** The result data of the last {@link #deliverInvokeAgain}. */
    public volatile JsonNode lastReplayed;
    /** When set, answers a published fact at once, inside the publication (an instantly answering counterpart). */
    public volatile Function<CloudEvent, CloudEvent> instantAnswer;

    public OrdersFixture() throws Exception {
        registry.register(OrderDefinitions.placeOrder(T1, T2));
        BlobStore blobs = new FilesystemBlobStore(Files.createTempDirectory("sandbox-orders-test"));
        OrdersHandlers handlers = new OrdersHandlers(documents, markers, mail, clock, "orders@sandbox.local",
                "sandbox-facts");
        worker = new WorkerRuntime(handlers.registry(), blobs);
        InMemoryTaskTransport transport = new InMemoryTaskTransport();
        transport.connectWorker(invoke -> {
            invokes.add(invoke);
            return worker.handle(invoke);
        });
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        publisher.subscribe(fact -> {
            allFacts.add(fact);
            facts.add(fact);
            Function<CloudEvent, CloudEvent> answer = instantAnswer;
            CloudEvent reply = answer == null ? null : answer.apply(fact);
            return reply == null ? CompletableFuture.completedFuture(null) : bus.publish(reply);
        });
        engine = new WorkflowEngine(registry, transport, state, timers, blobs,
                new TreeInterpreter(new JqExpressionEvaluator()), clock, publisher, blobs);
        TriggerBindings bindings = new TriggerBindings(registry);
        bindings.bindDeclaredSchedules();
        new DomainEventRouter(List.of(new StartReaction(bindings, engine), new ResumeReaction(engine)))
                .subscribeTo(bus);
    }

    public static ObjectNode order(String orderId, double amount) {
        ObjectNode order = Json.obj();
        order.put("orderId", orderId);
        order.put("customerId", "c-1");
        order.put("recipient", "customer@example.com");
        order.put("amount", amount);
        order.put("currency", "BRL");
        ObjectNode item = order.putArray("items").addObject();
        item.put("sku", "sku-1");
        item.put("quantity", 1);
        item.put("unitPrice", amount);
        return order;
    }

    /** POST /orders' event, delivered as the domain queue would; returns the instance id the endpoint answers. */
    public String place(ObjectNode order) throws Exception {
        CloudEvent placed = Orders.placed(order);
        deliver(placed);
        return Orders.instanceId(order.get("orderId").asText());
    }

    public void deliver(CloudEvent event) throws Exception {
        bus.publish(event).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A contract invoice-issued.v1 for an order, citing a document this fixture can read. */
    public CloudEvent issued(String orderId, String eventId) {
        String invoiceId = "inv-" + orderId;
        URI document = URI.create("s3://sandbox-facts/billing/invoices/" + invoiceId + ".html");
        documents.byUri.put(document, ("<html>invoice " + invoiceId + "</html>").getBytes(StandardCharsets.UTF_8));
        ObjectNode data = Json.obj();
        data.put("orderId", orderId);
        data.put("invoiceId", invoiceId);
        data.put("amount", 10);
        data.put("currency", "BRL");
        data.put("issuedAt", clock.instant().toString());
        data.put("invoiceDocument", document.toString());
        return answer(eventId, Orders.INVOICE_ISSUED, data);
    }

    public CloudEvent rejected(String orderId, String eventId, String reason) {
        ObjectNode data = Json.obj();
        data.put("orderId", orderId);
        data.put("reason", reason);
        return answer(eventId, Orders.INVOICE_REJECTED, data);
    }

    private CloudEvent answer(String eventId, String type, JsonNode data) {
        return CloudEventBuilder.v1().withId(eventId).withSource(ISSUER).withType(type)
                .withDataContentType("application/json").withData(Json.bytes(data)).build();
    }

    /** Waits until the instance is saved waiting on a listen (not on a task). */
    public InstanceSnapshot awaitListening(String instanceId) throws InterruptedException {
        while (true) {
            InstanceSnapshot waiting = state.waiting().poll(10, TimeUnit.SECONDS);
            if (waiting == null) {
                throw new AssertionError("instance " + instanceId + " did not wait on a listen within 10 s");
            }
            if (waiting.instanceId().equals(instanceId) && waiting.waitingKey().startsWith("event:")) {
                return waiting;
            }
        }
    }

    /** The latest timer armed for an instance (a listen's timeout). */
    public TimerRequest lastTimerOf(String instanceId) {
        return timers.armed.stream().filter(req -> instanceId.equals(req.workflowInstanceId()))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** Moves the clock to a timer's end and fires what is due. */
    public void fire(TimerRequest timer) {
        clock.set(timer.fireAt());
        timers.delegate.tick();
    }

    public JsonNode awaitCompleted(String instanceId) throws Exception {
        return engine.awaitCompletion(instanceId, Duration.ofSeconds(10)).toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    public InstanceSnapshot load(String instanceId) throws Exception {
        return state.load(instanceId).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
    }

    public CloudEvent nextFact() throws InterruptedException {
        CloudEvent fact = facts.poll(10, TimeUnit.SECONDS);
        if (fact == null) {
            throw new AssertionError("no fact was published within 10 s");
        }
        return fact;
    }

    /** Hands an invocation to the worker again, as an SQS redelivery would. */
    public void deliverInvokeAgain(CloudEvent invoke) throws Exception {
        CloudEvent result = worker.handle(invoke).toCompletableFuture().get(10, TimeUnit.SECONDS);
        lastReplayed = Json.read(result.getData().toBytes());
    }

    public List<CloudEvent> invokesOf(String route) {
        return invokes.stream().filter(invoke -> route.equals(invoke.getSubject())).toList();
    }

    public List<CloudEvent> factsOf(String type) {
        return allFacts.stream().filter(fact -> type.equals(fact.getType())).toList();
    }

    public static final class Documents implements InvoiceDocuments {
        public final Map<URI, byte[]> byUri = new ConcurrentHashMap<>();
        public final List<URI> reads = new CopyOnWriteArrayList<>();

        @Override
        public byte[] read(URI document) {
            reads.add(document);
            byte[] content = byUri.get(document);
            if (content == null) {
                throw new IllegalStateException("no such document: " + document);
            }
            return content;
        }
    }

    public static final class Markers implements EffectMarkers {
        public final Map<String, Instant> keys = new ConcurrentHashMap<>();

        @Override
        public Optional<Instant> markIfAbsent(String effectKey, Instant now) {
            return Optional.ofNullable(keys.putIfAbsent(effectKey, now));
        }
    }

    public record Mail(String from, String to, String subject, String body, Attachment attachment) {
    }

    public static final class CapturingMail implements MailSender {
        public final List<Mail> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String from, String to, String subject, String body, Attachment attachment) {
            sent.add(new Mail(from, to, subject, body, attachment));
        }
    }

    /** Remembers every armed and cancelled timer. */
    public static final class CapturingTimers implements TimerService {
        final InMemoryTimerService delegate;
        public final List<TimerRequest> armed = new CopyOnWriteArrayList<>();
        public final List<String> cancelled = new CopyOnWriteArrayList<>();

        CapturingTimers(InMemoryTimerService delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<String> schedule(TimerRequest req) {
            armed.add(req);
            return delegate.schedule(req);
        }

        @Override
        public CompletionStage<Void> cancel(String timerId) {
            cancelled.add(timerId);
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
