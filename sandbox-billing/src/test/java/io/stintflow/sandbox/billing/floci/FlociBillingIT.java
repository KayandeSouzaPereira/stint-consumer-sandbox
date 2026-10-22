package io.stintflow.sandbox.billing.floci;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.schedule.ScheduledStart;
import io.stintflow.sandbox.billing.Billing;
import io.stintflow.sandbox.billing.BillingEngine;
import io.stintflow.sandbox.billing.worker.FaultInjection;
import io.stintflow.spi.ScheduleFire;
import io.stintflow.spi.ScheduleSpec;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.2, CA1-CA5 on floci: the whole sandbox-billing app (engine + worker, {@link BillingEngine}) over its
 * real queues, tables, bus, Scheduler and MailHog. Requests are published on {@code sandbox-domain} as any
 * producer would; the outcome is read from the spy queue, the tables and MailHog.
 * <p>
 * Run with {@code mvn verify -Pfloci -pl sandbox-billing}, after {@code docker compose up -d} and
 * {@code infra/bootstrap.sh}, with no other sandox-billing running (it would consume the same queues).
 */
@QuarkusTest
@Tag("floci")
class FlociBillingIT {

    private static final URI REQUESTER = URI.create("https://sandbox.stintflow.io/test-requester");
    private static final Duration FLOW = Duration.ofSeconds(90);

    @Inject
    BillingEngine billing;
    @Inject
    EventBridgeClient eventBridge;
    @Inject
    SqsClient sqs;
    @Inject
    DynamoDbClient ddb;

    FlociProbe probe;
    final List<String> wireFailures = new CopyOnWriteArrayList<>();
    final Handler failures = new Handler() {
        @Override
        public void publish(LogRecord record) {
            String message = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage();
            if (message != null && message.contains(" failed: type=")) {
                wireFailures.add(message);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void setUp() {
        probe = new FlociProbe(eventBridge, sqs);
        java.util.logging.Logger.getLogger(BillingEngine.class.getName()).addHandler(failures);
    }

    @AfterEach
    void tearDown() {
        java.util.logging.Logger.getLogger(BillingEngine.class.getName()).removeHandler(failures);
    }

    @Test
    void ca1_a_request_issues_an_invoice_with_the_request_lineage() throws Exception {
        String orderId = "o-ca1-" + UUID.randomUUID();
        CloudEvent request = request(orderId, 150.75, "BRL", "chn-ca1-" + UUID.randomUUID());

        probe.publish(request);
        JsonNode context = awaitCompleted(WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, request));
        CloudEvent issued = probe.spy(FlociProbe.ofOrder(Billing.INVOICE_ISSUED, orderId), seen -> !seen.isEmpty(),
                FLOW).getFirst();

        JsonNode data = Json.read(issued.getData().toBytes());
        String invoiceId = Billing.invoiceId(orderId);
        assertThat(data.get("invoiceId").asText()).isEqualTo(invoiceId);
        assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("150.75");
        assertThat(data.get("invoiceDocument").asText())
                .isEqualTo("s3://sandbox-facts/billing/invoices/" + invoiceId + ".html");
        assertThat(context.get("renderAttempt").asInt()).isEqualTo(1);
        // Sec. 8f: the request's chain; the direct cause is record-invoice's result, not the request.
        assertThat(issued.getExtension(StintEvents.EXT_CHAIN_ID)).isEqualTo(request.getExtension(StintEvents.EXT_CHAIN_ID));
        assertThat(issued.getExtension(StintEvents.EXT_CAUSATION_ID)).isNotNull().isNotEqualTo(request.getId());
        assertThat(issued.getExtension(StintEvents.EXT_TRACEPARENT)).isNotNull();
        assertThat(invoiceItem(invoiceId)).containsEntry("orderId", AttributeValue.fromS(orderId));
        System.out.printf("CA1 request id=%s chainid=%s -> invoice-issued id=%s chainid=%s causationid=%s data=%s%n",
                request.getId(), request.getExtension(StintEvents.EXT_CHAIN_ID), issued.getId(),
                issued.getExtension(StintEvents.EXT_CHAIN_ID), issued.getExtension(StintEvents.EXT_CAUSATION_ID), data);
    }

    @Test
    void ca2_a_duplicated_request_issues_one_invoice() throws Exception {
        String orderId = "o-ca2-" + UUID.randomUUID();
        CloudEvent request = request(orderId, 20, "BRL", "chn-ca2-" + UUID.randomUUID());
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> publishTogether(request, together));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> publishTogether(request, together));
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        awaitCompleted(WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, request));

        // Both copies reach the bus (the spy sees two requests); exactly one invoice comes out.
        List<CloudEvent> seen = probe.spy(event -> FlociProbe.ofOrder(Billing.INVOICE_REQUESTED, orderId).test(event)
                        || FlociProbe.ofOrder(Billing.INVOICE_ISSUED, orderId).test(event),
                events -> count(events, Billing.INVOICE_REQUESTED) == 2 && count(events, Billing.INVOICE_ISSUED) >= 1,
                FLOW);
        assertThat(count(seen, Billing.INVOICE_REQUESTED)).isEqualTo(2);
        assertThat(count(seen, Billing.INVOICE_ISSUED)).isEqualTo(1);
        System.out.printf("CA2 order %s: %d invoice-requested on the bus, %d invoice-issued%n", orderId,
                count(seen, Billing.INVOICE_REQUESTED), count(seen, Billing.INVOICE_ISSUED));
    }

    @Test
    void ca3_a_transient_503_succeeds_on_the_third_attempt() throws Exception {
        String orderId = FaultInjection.FAIL_503_PREFIX + "o-ca3-" + UUID.randomUUID();
        CloudEvent request = request(orderId, 33, "USD", "chn-ca3-" + UUID.randomUUID());
        String instanceId = WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, request);

        probe.publish(request);
        JsonNode context = awaitCompleted(instanceId);
        probe.spy(FlociProbe.ofOrder(Billing.INVOICE_ISSUED, orderId), seen -> !seen.isEmpty(), FLOW);

        assertThat(context.get("renderAttempt").asInt()).isEqualTo(3);
        List<String> mine = wireFailures.stream().filter(line -> line.contains(instanceId)).toList();
        assertThat(mine).hasSize(2).allSatisfy(line -> assertThat(line)
                .contains("render-invoice")
                .contains("type=" + Billing.COMMUNICATION_ERROR)
                .contains("status=503"));
        assertThat(mine.get(0)).contains("attempt 1 ");
        assertThat(mine.get(1)).contains("attempt 2 ");
        mine.forEach(line -> System.out.println("CA3 " + line));
    }

    @Test
    void ca4_an_invalid_request_is_rejected_without_an_invoice() throws Exception {
        String orderId = "o-ca4-" + UUID.randomUUID();
        CloudEvent request = request(orderId, 0, "BRL", "chn-ca4-" + UUID.randomUUID());

        probe.publish(request);
        awaitCompleted(WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, request));
        CloudEvent rejected = probe.spy(FlociProbe.ofOrder(Billing.INVOICE_REJECTED, orderId),
                seen -> !seen.isEmpty(), FLOW).getFirst();

        JsonNode data = Json.read(rejected.getData().toBytes());
        assertThat(data.get("reason").asText()).isEqualTo("invalid-amount");
        assertThat(rejected.getSource()).isEqualTo(Billing.SOURCE);
        assertThat(rejected.getExtension(StintEvents.EXT_CAUSATION_ID)).isEqualTo(request.getId());
        assertThat(invoiceItem(Billing.invoiceId(orderId))).isEmpty();
        System.out.printf("CA4 order %s -> invoice-rejected id=%s causationid=%s data=%s%n", orderId,
                rejected.getId(), rejected.getExtension(StintEvents.EXT_CAUSATION_ID), data);
    }

    @Test
    void ca5_the_scheduler_runs_the_summary_with_the_right_count_and_a_duplicate_sends_no_second_mail()
            throws Exception {
        String a = "o-ca5a-" + UUID.randomUUID();
        String b = "o-ca5b-" + UUID.randomUUID();
        CloudEvent requestA = request(a, 10, "BRL", "chn-ca5a-" + UUID.randomUUID());
        CloudEvent requestB = request(b, 11, "BRL", "chn-ca5b-" + UUID.randomUUID());
        probe.publish(requestA);
        probe.publish(requestB);
        Instant issuedA = Instant.parse(awaitCompleted(
                WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, requestA)).get("issuedAt").asText());
        Instant issuedB = Instant.parse(awaitCompleted(
                WorkflowEngine.triggeredInstanceId(Billing.ISSUE_INVOICE, requestB)).get("issuedAt").asText());

        // The first occurrence after both invoices: its window [T - 1 min, T) holds the later one at least.
        Instant t = (issuedA.isAfter(issuedB) ? issuedA : issuedB).truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        ScheduleSpec spec = billing.registry().scheduleSpecs().stream()
                .filter(s -> s.definition().equals(Billing.BILLING_SUMMARY)).findFirst().orElseThrow();
        String summaryId = WorkflowEngine.triggeredInstanceId(Billing.BILLING_SUMMARY,
                ScheduledStart.syntheticEvent(spec, t));
        JsonNode summary = billing.engine().awaitCompletion(summaryId, Duration.ofMinutes(3)).toCompletableFuture()
                .get(4, TimeUnit.MINUTES);

        long expected = countIssued(t.minus(Duration.ofMinutes(1)), t);
        long mineInWindow = List.of(issuedA, issuedB).stream()
                .filter(at -> !at.isBefore(t.minus(Duration.ofMinutes(1)))).count();
        assertThat(summary.get("invoiceCount").asLong()).isEqualTo(expected).isGreaterThanOrEqualTo(mineInWindow);
        assertThat(summary.get("mailed").asBoolean()).isTrue();
        String subject = "Billing summary " + t.minus(Duration.ofMinutes(1)) + " to " + t + " UTC: " + expected
                + " invoice(s)";
        assertThat(subjectsTo("finance@sandbox.local")).filteredOn(subject::equals).hasSize(1);

        // A duplicate of the occurrence (a redelivered fire) starts nothing and mails nothing.
        billing.scheduledStart().handle(new ScheduleFire(spec.name(), spec.definition(), t, Instant.now()))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        billing.engine().awaitCompletion(summaryId, Duration.ofSeconds(30)).toCompletableFuture()
                .get(60, TimeUnit.SECONDS);
        assertThat(subjectsTo("finance@sandbox.local")).filteredOn(subject::equals).hasSize(1);
        System.out.printf("CA5 occurrence %s (instance %s): count=%d (scan of billing-invoices: %d), mail \"%s\" x1, "
                + "after a duplicate fire still x1%n", t, summaryId, summary.get("invoiceCount").asLong(), expected, subject);
    }

    private JsonNode awaitCompleted(String instanceId) throws Exception {
        return billing.engine().awaitCompletion(instanceId, FLOW).toCompletableFuture()
                .get(FLOW.toSeconds() + 30, TimeUnit.SECONDS);
    }

    private void publishTogether(CloudEvent request, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            probe.publish(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, AttributeValue> invoiceItem(String invoiceId) {
        return ddb.getItem(r -> r.tableName("billing-invoices").consistentRead(true)
                .key(Map.of("invoiceId", AttributeValue.fromS(invoiceId)))).item();
    }

    /** An independent count of billing-invoices in a window (the test's own scan, not the handler's). */
    private long countIssued(Instant from, Instant to) {
        return ddb.scanPaginator(r -> r.tableName("billing-invoices").select(Select.ALL_ATTRIBUTES).consistentRead(true))
                .items().stream()
                .map(item -> Instant.parse(item.get("issuedAt").s()))
                .filter(at -> !at.isBefore(from) && at.isBefore(to))
                .count();
    }

    private List<String> subjectsTo(String address) throws Exception {
        return probe.mailsTo(address).stream().map(mail -> mail[0]).toList();
    }

    private static long count(List<CloudEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.getType())).count();
    }

    private static CloudEvent request(String orderId, double amount, String currency, String chainId) {
        ObjectNode data = Json.obj();
        data.put("orderId", orderId);
        data.put("customerId", "c-floci");
        data.put("amount", amount);
        data.put("currency", currency);
        return CloudEventBuilder.v1()
                .withId("req-" + UUID.randomUUID())
                .withSource(REQUESTER)
                .withType(Billing.INVOICE_REQUESTED)
                .withDataContentType("application/json")
                .withData(Json.bytes(data))
                .withExtension(StintEvents.EXT_CHAIN_ID, chainId)
                .build();
    }
}
