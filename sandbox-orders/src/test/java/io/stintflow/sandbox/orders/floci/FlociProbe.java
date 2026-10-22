package io.stintflow.sandbox.orders.floci;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * SDD S.3, floci tests: what a test watches from outside Orders — the spy queue and MailHog. Every wait is an SQS
 * long poll or an engine completion, never a sleep. The spy is shared, so each test filters by its own order.
 */
public final class FlociProbe {

    public static final String SPY = "http://localhost:4566/000000000000/sandbox-spy";
    public static final URI MAILHOG = URI.create("http://localhost:8025");

    private final SqsClient sqs;
    private final HttpClient http = HttpClient.newHttpClient();

    public FlociProbe(SqsClient sqs) {
        this.sqs = sqs;
    }

    /**
     * Collects the spy's events that match, until {@code enough} holds for them and one more long poll comes back
     * empty (so a late duplicate would have been seen), or the deadline passes.
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

    /** The spy's events (any type) of one order. */
    public static Predicate<CloudEvent> ofOrder(String orderId) {
        return event -> event.getData() != null
                && orderId.equals(Json.read(event.getData().toBytes()).path("orderId").asText());
    }

    public static long count(List<CloudEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.getType())).count();
    }

    public static CloudEvent first(List<CloudEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.getType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " among " + events.size() + " event(s)"));
    }

    /** A received e-mail: subject, text body and its attachments. */
    public record Mail(String subject, String body, List<Attachment> attachments) {
    }

    public record Attachment(String name, byte[] content) {
    }

    /** MailHog's messages addressed to someone. */
    public List<Mail> mailsTo(String address) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(MAILHOG.resolve(
                "/api/v2/search?kind=to&limit=250&query=" + URLEncoder.encode(address, StandardCharsets.UTF_8)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        List<Mail> mails = new ArrayList<>();
        for (JsonNode item : Json.MAPPER.readTree(response.body()).path("items")) {
            String subject = item.at("/Content/Headers/Subject/0").asText();
            String body = item.at("/Content/Body").asText();
            List<Attachment> attachments = new ArrayList<>();
            for (JsonNode part : item.at("/MIME/Parts")) {
                String disposition = part.at("/Headers/Content-Disposition/0").asText();
                if (!disposition.startsWith("attachment")) {
                    if (part.at("/Headers/Content-Type/0").asText().startsWith("text/plain")) {
                        body = decode(part).length > 0 ? new String(decode(part), StandardCharsets.UTF_8) : body;
                    }
                    continue;
                }
                String name = disposition.replaceAll(".*filename=\"?([^\";]+)\"?.*", "$1");
                attachments.add(new Attachment(name, decode(part)));
            }
            mails.add(new Mail(subject, body, attachments));
        }
        return mails;
    }

    private static byte[] decode(JsonNode part) {
        String body = part.path("Body").asText();
        String encoding = part.at("/Headers/Content-Transfer-Encoding/0").asText();
        if ("base64".equalsIgnoreCase(encoding)) {
            return Base64.getMimeDecoder().decode(body);
        }
        if ("quoted-printable".equalsIgnoreCase(encoding)) {
            return body.replace("=\r\n", "").replace("=\n", "").getBytes(StandardCharsets.UTF_8);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
