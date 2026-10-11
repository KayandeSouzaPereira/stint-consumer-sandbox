package io.stintflow.sandbox.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.stintflow.sandbox.orders.support.OrdersFixture;
import io.stintflow.sandbox.orders.worker.OrdersHandlers;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.TimerRequest;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.3, CA8: {@code orders:place-order:1.0.0} on the in-memory engine, through the real handlers, one test per
 * branch — invoiced, rejected, timeout followed by a late invoice, both timeouts — plus the in-memory side of CA4
 * (an instant answer), CA5 (a repeated order) and CA6 (a repeated invoice).
 */
class PlaceOrderWorkflowTest {

    @Test
    void invoiced_order_is_confirmed_with_the_invoice_attached_on_the_order_chain() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-ok", 10));

        CloudEvent requested = f.nextFact();
        assertThat(requested.getType()).isEqualTo(Orders.INVOICE_REQUESTED);
        assertThat(requested.getSource()).isEqualTo(Orders.SOURCE);
        JsonNode request = Json.read(requested.getData().toBytes());
        assertThat(request.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("orderId", "customerId", "amount", "currency", "items");
        assertThat(f.load(instanceId).context().get("status").asText()).isEqualTo("requested");

        f.awaitListening(instanceId);
        CloudEvent issued = f.issued("o-ok", "issued-1");
        f.deliver(issued);
        JsonNode context = f.awaitCompleted(instanceId);
        CloudEvent confirmed = f.nextFact();

        assertThat(context.get("status").asText()).isEqualTo("confirmed");
        assertThat(confirmed.getType()).isEqualTo(Orders.ORDER_CONFIRMED);
        JsonNode data = Json.read(confirmed.getData().toBytes());
        assertThat(data.get("orderId").asText()).isEqualTo("o-ok");
        assertThat(data.get("invoiceId").asText()).isEqualTo("inv-o-ok");
        assertThat(data.get("confirmedAt").asText()).isEqualTo(OrdersFixture.START.toString());
        assertThat(f.mail.sent).singleElement().satisfies(mail -> {
            assertThat(mail.from()).isEqualTo("orders@sandbox.local");
            assertThat(mail.to()).isEqualTo("customer@example.com");
            assertThat(mail.subject()).isEqualTo("Order o-ok confirmed: invoice inv-o-ok");
            assertThat(mail.attachment().name()).isEqualTo("invoice-inv-o-ok.html");
            assertThat(new String(mail.attachment().content(), StandardCharsets.UTF_8))
                    .isEqualTo("<html>invoice inv-o-ok</html>");
        });
        // RF3: exactly the cited document, once.
        assertThat(f.documents.reads).containsExactly(java.net.URI.create(
                "s3://sandbox-facts/billing/invoices/inv-o-ok.html"));
        // CA1: every fact on the order's chain; the confirmation's cause chain goes through the invoice.
        String chain = Orders.chainId("o-ok");
        assertThat(requested.getExtension(StintEvents.EXT_CHAIN_ID)).isEqualTo(chain);
        assertThat(confirmed.getExtension(StintEvents.EXT_CHAIN_ID)).isEqualTo(chain);
        CloudEvent confirmation = f.invokesOf(Orders.ROUTE_SEND_CONFIRMATION).getFirst();
        assertThat(confirmation.getExtension(StintEvents.EXT_CAUSATION_ID)).isEqualTo(issued.getId());
        assertThat(f.timers.cancelled).contains(f.lastTimerOf(instanceId).timerId()); // the listen timeout ended
    }

    @Test
    void rejected_order_mails_the_reason_and_confirms_nothing() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-zero", 0));
        f.nextFact();
        f.awaitListening(instanceId);

        f.deliver(f.rejected("o-zero", "rejected-1", "invalid-amount"));
        JsonNode context = f.awaitCompleted(instanceId);

        assertThat(context.get("status").asText()).isEqualTo("rejected");
        assertThat(context.get("reason").asText()).isEqualTo("invalid-amount");
        assertThat(f.mail.sent).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Order o-zero was not invoiced: invalid-amount");
            assertThat(mail.body()).contains("the amount must be a number greater than zero");
            assertThat(mail.attachment()).isNull();
        });
        assertThat(f.factsOf(Orders.ORDER_CONFIRMED)).isEmpty();
    }

    @Test
    void timeout_mails_a_delay_notice_and_a_late_invoice_still_confirms_the_order() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-late", 10));
        f.nextFact();
        f.awaitListening(instanceId);
        TimerRequest t1 = f.lastTimerOf(instanceId);
        assertThat(t1.fireAt()).isEqualTo(OrdersFixture.START.plus(OrdersFixture.T1));

        f.fire(t1);
        f.awaitListening(instanceId); // the second listen, after the delay notice
        TimerRequest t2 = f.lastTimerOf(instanceId);
        assertThat(t2.fireAt()).isEqualTo(t1.fireAt().plus(OrdersFixture.T2));
        assertThat(f.load(instanceId).context().get("status").asText()).isEqualTo("invoice-delayed");
        assertThat(f.mail.sent).singleElement().satisfies(mail ->
                assertThat(mail.subject()).isEqualTo("Order o-late: invoicing is delayed"));

        f.deliver(f.issued("o-late", "issued-late"));
        JsonNode context = f.awaitCompleted(instanceId);
        assertThat(context.get("status").asText()).isEqualTo("confirmed");
        assertThat(f.factsOf(Orders.ORDER_CONFIRMED)).hasSize(1);
        assertThat(f.mail.sent).extracting(OrdersFixture.Mail::subject).containsExactly(
                "Order o-late: invoicing is delayed", "Order o-late confirmed: invoice inv-o-late");

        // S.3 8c: the long timer was cancelled, and its late fire changes nothing.
        assertThat(f.timers.cancelled).contains(t2.timerId());
        f.fire(t2);
        assertThat(f.load(instanceId).status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(f.mail.sent).hasSize(2);
    }

    @Test
    void no_answer_within_both_timeouts_leaves_the_invoice_missing() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-silent", 10));
        f.nextFact();
        f.awaitListening(instanceId);
        f.fire(f.lastTimerOf(instanceId));
        f.awaitListening(instanceId);
        f.fire(f.lastTimerOf(instanceId));

        JsonNode context = f.awaitCompleted(instanceId);
        assertThat(context.get("status").asText()).isEqualTo("invoice-missing");
        assertThat(f.mail.sent).extracting(OrdersFixture.Mail::subject)
                .containsExactly("Order o-silent: invoicing is delayed");
        assertThat(f.factsOf(Orders.ORDER_CONFIRMED)).isEmpty();
    }

    @Test
    void an_answer_published_before_the_request_returns_still_finds_the_wait() throws Exception {
        OrdersFixture f = new OrdersFixture();
        f.instantAnswer = fact -> Orders.INVOICE_REQUESTED.equals(fact.getType())
                ? f.issued(Json.read(fact.getData().toBytes()).get("orderId").asText(), "instant-" + fact.getId())
                : null;
        for (int i = 1; i <= 20; i++) {
            String instanceId = f.place(OrdersFixture.order("o-fast-" + i, i));
            assertThat(f.awaitCompleted(instanceId).get("status").asText()).isEqualTo("confirmed");
        }
        assertThat(f.factsOf(Orders.ORDER_CONFIRMED)).hasSize(20);
        assertThat(f.mail.sent).hasSize(20);
    }

    @Test
    void the_same_order_placed_twice_at_once_starts_one_instance_and_mails_once() throws Exception {
        OrdersFixture f = new OrdersFixture();
        ObjectNode order = OrdersFixture.order("o-twice", 10);
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> placeTogether(f, order, together));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> placeTogether(f, order, together));
        String instanceId = first.get(15, TimeUnit.SECONDS);
        assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(instanceId);
        f.awaitListening(instanceId);
        f.deliver(f.issued("o-twice", "issued-twice"));
        f.awaitCompleted(instanceId);

        assertThat(f.state.creates()).isEqualTo(1);
        assertThat(f.factsOf(Orders.INVOICE_REQUESTED)).hasSize(1);
        assertThat(f.mail.sent).hasSize(1);
    }

    @Test
    void the_same_invoice_delivered_twice_mails_one_confirmation() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-dup", 10));
        f.awaitListening(instanceId);
        CloudEvent issued = f.issued("o-dup", "issued-dup");

        f.deliver(issued);
        f.awaitCompleted(instanceId);
        f.deliver(issued);

        assertThat(f.mail.sent).hasSize(1);
        assertThat(f.factsOf(Orders.ORDER_CONFIRMED)).hasSize(1);
        assertThat(f.invokesOf(Orders.ROUTE_SEND_CONFIRMATION)).hasSize(1);
    }

    @Test
    void a_redelivered_mail_task_does_not_send_again_and_keeps_its_first_instant() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-redeliver", 10));
        f.awaitListening(instanceId);
        f.deliver(f.issued("o-redeliver", "issued-redeliver"));
        f.awaitCompleted(instanceId);
        CloudEvent invoke = f.invokesOf(Orders.ROUTE_SEND_CONFIRMATION).getFirst();
        assertThat(f.markers.keys).containsKey(
                OrdersHandlers.mailEffectKey(instanceId, invoke.getExtension(StintEvents.EXT_TASK_ID).toString()));

        f.clock.set(OrdersFixture.START.plus(Duration.ofMinutes(5)));
        f.deliverInvokeAgain(invoke);

        assertThat(f.mail.sent).hasSize(1);
        assertThat(f.lastReplayed.at("/output/mailed").asBoolean()).isFalse();
        assertThat(f.lastReplayed.at("/output/sentAt").asText()).isEqualTo(OrdersFixture.START.toString());
    }

    @Test
    void a_document_outside_the_contract_pattern_is_never_read() throws Exception {
        OrdersFixture f = new OrdersFixture();
        String instanceId = f.place(OrdersFixture.order("o-rf3", 10));
        f.awaitListening(instanceId);
        ObjectNode data = (ObjectNode) Json.read(f.issued("o-rf3", "issued-rf3").getData().toBytes());
        data.put("invoiceDocument", "s3://sandbox-facts/somewhere-else/inv-o-rf3.html");

        f.deliver(io.cloudevents.core.builder.CloudEventBuilder.v1(f.issued("o-rf3", "issued-rf3"))
                .withData(Json.bytes(data)).build());

        assertThatThrownBy(() -> f.awaitCompleted(instanceId)).hasMessageContaining("failed");
        assertThat(f.documents.reads).isEmpty();
        assertThat(f.mail.sent).isEmpty();
        assertThat(f.markers.keys).isEmpty();
    }

    @Test
    void listen_timeouts_under_two_seconds_are_refused_at_load() {
        assertThatThrownBy(() -> OrderDefinitions.placeOrder(Duration.ofSeconds(1), Duration.ofHours(1)))
                .hasMessageContaining("S.2 8i");
        assertThat(OrderDefinitions.placeOrder(Duration.ofSeconds(2), Duration.ofMinutes(20)).ref())
                .isEqualTo(Orders.PLACE_ORDER);
    }

    private static String placeTogether(OrdersFixture f, ObjectNode order, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            return f.place(order);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
