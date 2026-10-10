package io.stintflow.sandbox.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.stintflow.sandbox.billing.aws.S3InvoiceDocuments;
import io.stintflow.sandbox.billing.support.BillingFixture;
import io.stintflow.sandbox.billing.support.InMemoryBillingPorts;
import io.stintflow.sandbox.billing.worker.FaultInjection;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.2, CA8: {@code billing:issue-invoice:1.0.0} on the in-memory engine, through the real handlers — a valid
 * request, invalid ones, a transient 503 retried to success, and retries exhausted. Plus the in-memory side of CA1
 * (lineage, sec. 8f) and CA2 (a duplicated request).
 */
class IssueInvoiceWorkflowTest {

    @Test
    void valid_request_issues_the_invoice_on_the_request_chain() throws Exception {
        BillingFixture f = new BillingFixture();
        CloudEvent request = BillingFixture.request("req-1", "chn-valid", order("o-valid", 125.5, "BRL"));

        f.deliver(request);
        JsonNode context = f.awaitCompleted(f.issueInvoiceInstance(request));
        CloudEvent issued = f.nextFact();

        String invoiceId = Billing.invoiceId("o-valid");
        assertThat(issued.getType()).isEqualTo(Billing.INVOICE_ISSUED);
        assertThat(issued.getSource()).isEqualTo(Billing.SOURCE);
        JsonNode data = Json.read(issued.getData().toBytes());
        assertThat(data.get("orderId").asText()).isEqualTo("o-valid");
        assertThat(data.get("invoiceId").asText()).isEqualTo(invoiceId);
        assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("125.5");
        assertThat(data.get("currency").asText()).isEqualTo("BRL");
        assertThat(data.get("issuedAt").asText()).isEqualTo(BillingFixture.START.toString());
        assertThat(data.get("invoiceDocument").asText())
                .isEqualTo("s3://sandbox-facts/" + S3InvoiceDocuments.key(invoiceId));
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("orderId", "invoiceId", "amount", "currency", "issuedAt", "invoiceDocument");

        // 8a: the document under Billing's prefix; 8c: one record.
        assertThat(f.documents.byKey).containsOnlyKeys("billing/invoices/" + invoiceId + ".html");
        assertThat(f.records.byId).containsOnlyKeys(invoiceId);
        assertThat(context.get("renderAttempt").asInt()).isEqualTo(1);

        // CA1, sec. 8f: same chain as the request; the direct cause is record-invoice's result, while the
        // instance's first activation was caused by the request itself.
        assertThat(issued.getExtension(StintEvents.EXT_CHAIN_ID)).isEqualTo("chn-valid");
        CloudEvent record = f.invokesOf(Billing.ROUTE_RECORD_INVOICE).getFirst();
        assertThat(issued.getExtension(StintEvents.EXT_CAUSATION_ID)).isEqualTo(record.getId());
        CloudEvent render = f.invokesOf(Billing.ROUTE_RENDER_INVOICE).getFirst();
        assertThat(render.getExtension(StintEvents.EXT_CAUSATION_ID)).isEqualTo(request.getId());
        assertThat(render.getExtension(StintEvents.EXT_CHAIN_ID)).isEqualTo("chn-valid");
    }

    @Test
    void invalid_requests_are_rejected_with_their_reason_and_no_invoice() throws Exception {
        BillingFixture f = new BillingFixture();
        ObjectNode noCurrency = order("o-no-currency", 10, "BRL");
        noCurrency.remove("currency");
        CloudEvent zero = BillingFixture.request("req-zero", "chn-zero", order("o-zero", 0, "BRL"));
        CloudEvent negative = BillingFixture.request("req-neg", "chn-neg", order("o-neg", -3, "BRL"));
        CloudEvent missing = BillingFixture.request("req-missing", "chn-missing", noCurrency);
        CloudEvent blank = BillingFixture.request("req-blank", "chn-blank", order("o-blank", 10, ""));

        for (CloudEvent request : new CloudEvent[] {zero, negative, missing, blank}) {
            f.deliver(request);
            f.awaitCompleted(f.issueInvoiceInstance(request));
            CloudEvent rejected = f.nextFact();
            assertThat(rejected.getType()).isEqualTo(Billing.INVOICE_REJECTED);
            assertThat(rejected.getSource()).isEqualTo(Billing.SOURCE);
            // No task ran: the rejection's direct cause is the request.
            assertThat(rejected.getExtension(StintEvents.EXT_CAUSATION_ID)).isEqualTo(request.getId());
            assertThat(rejected.getExtension(StintEvents.EXT_CHAIN_ID))
                    .isEqualTo(request.getExtension(StintEvents.EXT_CHAIN_ID));
        }

        assertThat(f.allFacts).extracting(fact -> Json.read(fact.getData().toBytes()))
                .extracting(d -> d.get("orderId").asText() + ":" + d.get("reason").asText())
                .containsExactly("o-zero:invalid-amount", "o-neg:invalid-amount", "o-no-currency:missing-currency",
                        "o-blank:missing-currency");
        assertThat(f.invokes).isEmpty();
        assertThat(f.records.byId).isEmpty();
        assertThat(f.documents.byKey).isEmpty();
    }

    @Test
    void transient_503_is_retried_and_succeeds_on_the_third_attempt() throws Exception {
        BillingFixture f = new BillingFixture();
        String orderId = FaultInjection.FAIL_503_PREFIX + "o-1";
        CloudEvent request = BillingFixture.request("req-503", "chn-503", order(orderId, 42, "USD"));

        f.deliver(request);
        InstanceSnapshot afterFirst = f.fireNextRetry();
        InstanceSnapshot afterSecond = f.fireNextRetry();
        JsonNode context = f.awaitCompleted(f.issueInvoiceInstance(request));

        assertThat(context.get("renderAttempt").asInt()).isEqualTo(3);
        assertThat(f.invokesOf(Billing.ROUTE_RENDER_INVOICE))
                .extracting(invoke -> invoke.getExtension(StintEvents.EXT_ATTEMPT).toString())
                .containsExactly("1", "2", "3");
        // CA3: the intermediate errors, on the wire and as caught, are the DSL 1.0 standard type with status 503.
        assertThat(f.results.stream().map(r -> Json.read(r.getData().toBytes()))
                .filter(d -> "FAILED".equals(d.get("status").asText())).toList())
                .hasSize(2)
                .allSatisfy(d -> {
                    assertThat(d.at("/error/type").asText()).isEqualTo(Billing.COMMUNICATION_ERROR.toString());
                    assertThat(d.at("/error/status").asInt()).isEqualTo(503);
                });
        for (InstanceSnapshot waiting : new InstanceSnapshot[] {afterFirst, afterSecond}) {
            assertThat(waiting.retryState().lastError().type()).isEqualTo(Billing.COMMUNICATION_ERROR);
            assertThat(waiting.retryState().lastError().status()).isEqualTo(503);
        }
        assertThat(afterFirst.retryState().attempt()).isEqualTo(1);
        assertThat(afterSecond.retryState().attempt()).isEqualTo(2);

        CloudEvent issued = f.nextFact();
        assertThat(issued.getType()).isEqualTo(Billing.INVOICE_ISSUED);
        assertThat(f.records.byId).containsOnlyKeys(Billing.invoiceId(orderId));
    }

    @Test
    void exhausted_retries_fail_the_instance_without_an_invoice_or_an_event() throws Exception {
        InMemoryBillingPorts.UnavailableDocuments unavailable = new InMemoryBillingPorts.UnavailableDocuments();
        BillingFixture f = new BillingFixture(unavailable);
        CloudEvent request = BillingFixture.request("req-down", "chn-down", order("o-down", 7, "EUR"));

        f.deliver(request);
        f.fireNextRetry();
        f.fireNextRetry();
        String instanceId = f.issueInvoiceInstance(request);

        // S.2 8g: the third failure exhausts the policy; the instance fails.
        assertThatThrownBy(() -> f.awaitCompleted(instanceId)).hasMessageContaining("failed");
        assertThat(f.load(instanceId).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(unavailable.attempts).hasValue(3);
        assertThat(f.invokesOf(Billing.ROUTE_RECORD_INVOICE)).isEmpty();
        assertThat(f.records.byId).isEmpty();
        assertThat(f.allFacts).isEmpty();
        assertThat(f.state.retryWaits()).isEmpty(); // no fourth attempt was scheduled
    }

    @Test
    void a_duplicated_request_starts_one_instance_and_issues_one_invoice() throws Exception {
        BillingFixture f = new BillingFixture();
        CloudEvent request = BillingFixture.request("req-dup", "chn-dup", order("o-dup", 9.99, "BRL"));
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> deliverTogether(f, request, together));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> deliverTogether(f, request, together));
        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
        f.awaitCompleted(f.issueInvoiceInstance(request));
        f.nextFact();

        assertThat(f.state.creates()).isEqualTo(1);
        assertThat(f.invokesOf(Billing.ROUTE_RENDER_INVOICE)).hasSize(1);
        assertThat(f.allFacts).hasSize(1);
        assertThat(f.records.byId).hasSize(1);
    }

    private static void deliverTogether(BillingFixture f, CloudEvent request, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            f.deliver(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode order(String orderId, double amount, String currency) {
        ObjectNode data = Json.obj();
        data.put("orderId", orderId);
        data.put("customerId", "c-1");
        data.put("amount", amount);
        data.put("currency", currency);
        ObjectNode item = data.putArray("items").addObject();
        item.put("sku", "sku-1");
        item.put("quantity", 1);
        item.put("unitPrice", amount);
        return data;
    }
}
