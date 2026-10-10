package io.stintflow.sandbox.billing.floci;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * SDD S.2, floci tests: what a test does from outside Billing — publish on the domain bus as any producer would,
 * and watch the spy queue and MailHog. Every wait is an SQS long poll or an engine completion, never a sleep.
 * The spy is shared, so each test filters by its own {@code orderId}; others' messages are left alone.
 */
public final class FlociProbe {

    public static final String BUS = "sandbox-domain";
    public static final String SPY = "http://localhost:4566/000000000000/sandbox-spy";
    public static final URI MAILHOG = URI.create("http://localhost:8025");

    private final EventBridgeClient eventBridge;
    private final SqsClient sqs;
    private final HttpClient http = HttpClient.newHttpClient();

    public FlociProbe(EventBridgeClient eventBridge, SqsClient sqs) {
        this.eventBridge = eventBridge;
        this.sqs = sqs;
    }

    /** Publishes a CloudEvent on the domain bus the way Stint's EventBridge publisher does. */
    public void publish(CloudEvent event) {
        PutEventsResponse response = eventBridge.putEvents(r -> r.entries(e -> e.eventBusName(BUS)
                .source(event.getSource().toString())
                .detailType(event.getType())
                .detail(new String(CeWire.toJson(event), StandardCharsets.UTF_8))));
        if (response.failedEntryCount() > 0) {
            throw new IllegalStateException("put-events failed: " + response.entries());
        }
    }

    /**
     * Collects the spy's events that match, until {@code enough} holds for them and one more long poll comes
     * back empty (so a late duplicate would have been seen), or the deadline passes.
     */
    public List<CloudEvent> spy(Predicate<CloudEvent> matching, Predicate<List<CloudEvent>> enough, Duration within) {
        List<CloudEvent> seen = new ArrayList<>();
        Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(SPY).maxNumberOfMessages(10)
                    .waitTimeSeconds(5).visibilityTimeout(1)).messages();
            if (messages.isEmpty() && enough.test(seen)) {
                return seen;
            }
            for (Message m : messages) {
                CloudEvent event = CeWire.fromJson(m.body().getBytes(StandardCharsets.UTF_8));
                if (matching.test(event)) {
                    seen.add(event);
                    sqs.deleteMessage(r -> r.queueUrl(SPY).receiptHandle(m.receiptHandle()));
                }
            }
        }
        return seen;
    }

    /** The spy's events of one type for one order. */
    public static Predicate<CloudEvent> ofOrder(String type, String orderId) {
        return event -> type.equals(event.getType()) && event.getData() != null
                && orderId.equals(Json.read(event.getData().toBytes()).path("orderId").asText());
    }

    /** MailHog's messages addressed to someone, as {subject, body} pairs. */
    public List<String[]> mailsTo(String address) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                MAILHOG.resolve("/api/v2/search?kind=to&limit=250&query=" + address)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        List<String[]> mails = new ArrayList<>();
        for (JsonNode item : Json.MAPPER.readTree(response.body()).path("items")) {
            mails.add(new String[] {item.at("/Content/Headers/Subject/0").asText(), item.at("/Content/Body").asText()});
        }
        return mails;
    }
}
