package io.stintflow.sandbox.scenarios.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * SDD S.4: everything that reaches the domain bus, through the {@code spy-all} rule's {@code sandbox-spy} queue
 * (contracts/events.md). The scenario owns the spy (floci is reset before it), so every message is drained into
 * {@link #events()}. A long poll on it is also how a scenario waits between two checks: never a sleep.
 */
public final class Spy {

    public static final ObjectMapper JSON = new ObjectMapper();
    static final String QUEUE = Floci.queueUrl("sandbox-spy");

    /** A CloudEvent as it went over the bus (structured JSON). */
    public record Event(String id, String type, String source, String chainId, String causationId, Instant time,
                        JsonNode data, String json, Instant seenAt) {

        public String orderId() {
            return data == null ? "" : data.path("orderId").asText();
        }
    }

    private final SqsClient sqs;
    private final List<Event> events = new ArrayList<>();

    public Spy(SqsClient sqs) {
        this.sqs = sqs;
    }

    /** One long poll (up to {@code wait}); whatever arrived is kept. Returns how many events arrived. */
    public int poll(Duration wait) {
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(QUEUE).maxNumberOfMessages(10)
                .waitTimeSeconds((int) Math.max(1, Math.min(20, wait.toSeconds())))).messages();
        Instant now = Instant.now();
        for (Message m : messages) {
            events.add(parse(m.body(), now));
            sqs.deleteMessage(r -> r.queueUrl(QUEUE).receiptHandle(m.receiptHandle()));
        }
        return messages.size();
    }

    /** Polls until {@code done} holds, failing after {@code within}. Each check is separated by one long poll. */
    public void until(String what, BooleanSupplier done, Duration within) {
        Instant deadline = Instant.now().plus(within);
        while (!done.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Not reached within " + within + ": " + what);
            }
            poll(Duration.ofSeconds(2));
        }
    }

    /** Polls until {@code enough} holds for the events seen so far. */
    public void untilEvents(String what, Predicate<List<Event>> enough, Duration within) {
        until(what, () -> enough.test(events), within);
    }

    /** Drains until a long poll of {@code quiet} comes back empty: a late duplicate would have been seen. */
    public void settle(Duration quiet) {
        while (poll(quiet) > 0) {
            // keep draining
        }
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public List<Event> of(String orderId) {
        return events.stream().filter(e -> orderId.equals(e.orderId())).toList();
    }

    public List<Event> ofType(String type) {
        return events.stream().filter(e -> type.equals(e.type())).toList();
    }

    public long count(String orderId, String type) {
        return events.stream().filter(e -> orderId.equals(e.orderId()) && type.equals(e.type())).count();
    }

    public Event first(String orderId, String type) {
        return events.stream().filter(e -> orderId.equals(e.orderId()) && type.equals(e.type())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " of " + orderId));
    }

    static Event parse(String body, Instant seenAt) {
        try {
            JsonNode ce = JSON.readTree(body);
            JsonNode data = ce.get("data");
            if (data != null && data.isTextual() && data.asText().startsWith("{")) {
                data = JSON.readTree(data.asText());
            }
            String time = ce.path("time").asText(null);
            return new Event(ce.path("id").asText(), ce.path("type").asText(), ce.path("source").asText(),
                    ce.path("chainid").asText(null), ce.path("causationid").asText(null),
                    time == null ? null : OffsetDateTime.parse(time).toInstant(), data, body, seenAt);
        } catch (IOException e) {
            throw new UncheckedIOException("Not a CloudEvent: " + body, e);
        }
    }
}
