package io.stintflow.sandbox.orders.floci;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.stintflow.sandbox.orders.Orders;
import io.stintflow.sandbox.orders.OrdersEngine;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.3, CA1, CA2 and CA4-CA7 on floci: the whole sandbox-orders app (engine + worker + HTTP) over its real queues,
 * tables and bus, MailHog for the e-mails, and the contract's counterpart running as its own process (S.3 8g): this
 * test never starts, stops or names it; it only checks that something answers at the counterpart's health URL.
 * <p>
 * Run with {@code mvn verify -Pfloci -pl sandbox-orders}, after {@code docker compose up -d},
 * {@code infra/bootstrap.sh} and starting the counterpart (README), with no other sandbox-orders running.
 */
@QuarkusTest
@Tag("floci")
class FlociOrdersIT {

    /** The counterpart's readiness URL; override with -Dorders.it.counterpart-health=... */
    private static final URI COUNTERPART = URI.create(
            System.getProperty("orders.it.counterpart-health", "http://localhost:8082/q/health/ready"));
    private static final Duration FLOW = Duration.ofSeconds(90);

    @TestHTTPResource("/orders")
    URL ordersUrl;
    @Inject
    OrdersEngine orders;
    @Inject
    SqsClient sqs;
    @Inject
    S3Client s3;
    @Inject
    EventBridgeClient eventBridge;
    @Inject
    DynamoDbClient ddb;

    final HttpClient http = HttpClient.newHttpClient();
    FlociProbe probe;

    @BeforeEach
    void setUp() throws Exception {
        probe = new FlociProbe(sqs);
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(COUNTERPART).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).as("the contract's counterpart must be running at %s (README)", COUNTERPART)
                .isEqualTo(200);
    }

    @Test
    void ca1_an_invoiced_order_is_confirmed_with_the_published_document_attached_on_one_chain() throws Exception {
        String orderId = "o-ca1-" + UUID.randomUUID();
        String recipient = orderId + "@example.com";

        JsonNode accepted = post(order(orderId, recipient, 25.5));
        assertThat(accepted.get("orderId").asText()).isEqualTo(orderId);
        assertThat(accepted.get("instanceId").asText()).isEqualTo(Orders.instanceId(orderId));
        JsonNode context = awaitCompleted(orderId);

        List<CloudEvent> events = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.ORDER_CONFIRMED) == 1, FLOW);
        List<String> types = events.stream().map(CloudEvent::getType).toList();
        assertThat(types).containsExactlyInAnyOrder(Orders.ORDER_PLACED, Orders.INVOICE_REQUESTED,
                Orders.INVOICE_ISSUED, Orders.ORDER_CONFIRMED);
        assertThat(events).extracting(e -> e.getExtension(StintEvents.EXT_CHAIN_ID))
                .containsOnly(Orders.chainId(orderId));

        // The attachment is byte for byte the document the invoice cites.
        CloudEvent issued = FlociProbe.first(events, Orders.INVOICE_ISSUED);
        JsonNode invoice = Json.read(issued.getData().toBytes());
        URI document = URI.create(invoice.get("invoiceDocument").asText());
        byte[] published = s3.getObjectAsBytes(r -> r.bucket(document.getHost()).key(document.getPath().substring(1)))
                .asByteArray();
        List<FlociProbe.Mail> mails = probe.mailsTo(recipient);
        assertThat(mails).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Order " + orderId + " confirmed: invoice " + invoice.get("invoiceId").asText());
            assertThat(mail.attachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.name()).isEqualTo("invoice-" + invoice.get("invoiceId").asText() + ".html");
                assertThat(attachment.content()).isEqualTo(published);
            });
        });
        assertThat(context.get("status").asText()).isEqualTo("confirmed");
        JsonNode status = get(orderId);
        assertThat(status.get("status").asText()).isEqualTo("confirmed");
        assertThat(status.get("invoiceId").asText()).isEqualTo(invoice.get("invoiceId").asText());
        System.out.printf("CA1 order %s: %s; chainid %s on all 4; attachment %d bytes == %s; GET %s%n", orderId, types,
                Orders.chainId(orderId), published.length, document, status);
    }

    @Test
    void ca2_a_rejected_order_mails_the_reason_and_confirms_nothing() throws Exception {
        String orderId = "o-ca2-" + UUID.randomUUID();
        String recipient = orderId + "@example.com";

        post(order(orderId, recipient, 0));
        JsonNode context = awaitCompleted(orderId);
        List<CloudEvent> events = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.INVOICE_REJECTED) == 1, FLOW);

        assertThat(FlociProbe.count(events, Orders.ORDER_CONFIRMED)).isZero();
        assertThat(context.get("status").asText()).isEqualTo("rejected");
        assertThat(probe.mailsTo(recipient)).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Order " + orderId + " was not invoiced: invalid-amount");
            assertThat(mail.attachments()).isEmpty();
        });
        JsonNode status = get(orderId);
        assertThat(status.get("status").asText()).isEqualTo("rejected");
        assertThat(status.get("reason").asText()).isEqualTo("invalid-amount");
        System.out.printf("CA2 order %s: %s; GET %s%n", orderId,
                events.stream().map(CloudEvent::getType).toList(), status);
    }

    @Test
    void ca4_twenty_orders_in_a_row_are_all_confirmed() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String orderId = "o-ca4-" + run + "-" + i;
            ids.add(orderId);
            post(order(orderId, orderId + "@example.com", i));
        }
        int confirmed = 0;
        for (String orderId : ids) {
            if ("confirmed".equals(awaitCompleted(orderId).get("status").asText())) {
                confirmed++;
            }
        }
        assertThat(confirmed).isEqualTo(20);
        for (String orderId : ids) {
            assertThat(probe.mailsTo(orderId + "@example.com")).as(orderId).hasSize(1);
        }
        System.out.printf("CA4 run %s: %d of 20 orders confirmed, one confirmation e-mail each%n", run, confirmed);
    }

    @Test
    void ca5_the_same_order_posted_twice_starts_one_instance_and_mails_once() throws Exception {
        String orderId = "o-ca5-" + UUID.randomUUID();
        String recipient = orderId + "@example.com";
        ObjectNode order = order(orderId, recipient, 12);
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> postTogether(order, together));
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() -> postTogether(order, together));
        String instanceA = first.get(30, TimeUnit.SECONDS).get("instanceId").asText();
        String instanceB = second.get(30, TimeUnit.SECONDS).get("instanceId").asText();
        awaitCompleted(orderId);
        List<CloudEvent> events = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.ORDER_PLACED) == 2 && FlociProbe.count(seen, Orders.ORDER_CONFIRMED) == 1,
                FLOW);

        assertThat(instanceA).isEqualTo(instanceB).isEqualTo(Orders.instanceId(orderId));
        assertThat(FlociProbe.count(events, Orders.ORDER_PLACED)).isEqualTo(2);      // both POSTs reached the bus
        assertThat(FlociProbe.count(events, Orders.INVOICE_REQUESTED)).isEqualTo(1); // one instance asked
        assertThat(FlociProbe.count(events, Orders.ORDER_CONFIRMED)).isEqualTo(1);
        assertThat(probe.mailsTo(recipient)).hasSize(1);
        System.out.printf("CA5 order %s: 2 POSTs -> instance %s (both), %d order-placed, %d invoice-requested, %d mail%n",
                orderId, instanceA, FlociProbe.count(events, Orders.ORDER_PLACED),
                FlociProbe.count(events, Orders.INVOICE_REQUESTED), probe.mailsTo(recipient).size());
    }

    @Test
    void ca6_the_same_invoice_delivered_twice_mails_one_confirmation() throws Exception {
        String orderId = "o-ca6-" + UUID.randomUUID();
        String recipient = orderId + "@example.com";
        post(order(orderId, recipient, 13));
        awaitCompleted(orderId);
        List<CloudEvent> events = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.ORDER_CONFIRMED) == 1, FLOW);
        CloudEvent issued = FlociProbe.first(events, Orders.INVOICE_ISSUED);

        // The very same event (same source and id) arrives again.
        eventBridge.putEvents(r -> r.entries(e -> e.eventBusName("sandbox-domain").source(issued.getSource().toString())
                .detailType(issued.getType()).detail(new String(CeWire.toJson(issued), StandardCharsets.UTF_8))));
        List<CloudEvent> again = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.INVOICE_ISSUED) == 1, FLOW);
        Map<String, AttributeValue> kept = awaitKeptInInbox(issued.getId());

        assertThat(FlociProbe.count(again, Orders.INVOICE_ISSUED)).isEqualTo(1);
        assertThat(FlociProbe.count(again, Orders.ORDER_CONFIRMED)).isZero();
        assertThat(kept).as("the duplicate was handled: no wait matched, so it was kept in the inbox").isNotEmpty();
        assertThat(probe.mailsTo(recipient)).hasSize(1);
        System.out.printf("CA6 order %s: invoice-issued %s delivered twice; second kept in orders-inbox (%s); 1 mail%n",
                orderId, issued.getId(), kept.get("waitKey").s());
    }

    @Test
    void ca7_an_order_the_counterpart_retries_is_confirmed_like_any_other() throws Exception {
        String orderId = "fail503-o-ca7-" + UUID.randomUUID();
        String recipient = orderId + "@example.com";

        post(order(orderId, recipient, 14));
        JsonNode context = awaitCompleted(orderId);
        List<CloudEvent> events = probe.spy(FlociProbe.ofOrder(orderId),
                seen -> FlociProbe.count(seen, Orders.ORDER_CONFIRMED) == 1, FLOW);

        assertThat(context.get("status").asText()).isEqualTo("confirmed");
        assertThat(probe.mailsTo(recipient)).hasSize(1);
        CloudEvent requested = FlociProbe.first(events, Orders.INVOICE_REQUESTED);
        CloudEvent issued = FlociProbe.first(events, Orders.INVOICE_ISSUED);
        System.out.printf("CA7 order %s: requested %s, issued %s (%.1f s later), confirmed%n", orderId,
                requested.getTime(), issued.getTime(),
                Duration.between(requested.getTime(), issued.getTime()).toMillis() / 1000.0);
    }

    private JsonNode awaitCompleted(String orderId) throws Exception {
        return orders.engine().awaitCompletion(Orders.instanceId(orderId), FLOW).toCompletableFuture()
                .get(FLOW.toSeconds() + 30, TimeUnit.SECONDS);
    }

    /** The duplicate's inbox entry (orders-inbox, SK eventId), waiting on SQS long polls of the spy between reads. */
    private Map<String, AttributeValue> awaitKeptInInbox(String eventId) {
        Instant deadline = Instant.now().plus(FLOW);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, AttributeValue>> items = ddb.scanPaginator(r -> r.tableName("orders-inbox")
                    .consistentRead(true).filterExpression("eventId = :e")
                    .expressionAttributeValues(Map.of(":e", AttributeValue.fromS(eventId)))).items().stream().toList();
            if (!items.isEmpty()) {
                return items.getFirst();
            }
            probe.spy(e -> false, seen -> true, Duration.ofSeconds(5)); // one long poll, not a sleep
        }
        return Map.of();
    }

    private JsonNode post(ObjectNode order) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(ordersUrl.toURI())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(order.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return Json.MAPPER.readTree(response.body());
    }

    private JsonNode postTogether(ObjectNode order, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            return post(order);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode get(String orderId) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(ordersUrl + "/" + orderId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return Json.MAPPER.readTree(response.body());
    }

    private static ObjectNode order(String orderId, String recipient, double amount) {
        ObjectNode order = Json.obj();
        order.put("orderId", orderId);
        order.put("customerId", "c-floci");
        order.put("recipient", recipient);
        order.put("amount", amount);
        order.put("currency", "BRL");
        ObjectNode item = order.putArray("items").addObject();
        item.put("sku", "sku-1");
        item.put("quantity", 1);
        item.put("unitPrice", amount);
        return order;
    }
}
