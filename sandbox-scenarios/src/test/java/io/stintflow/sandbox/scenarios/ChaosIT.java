package io.stintflow.sandbox.scenarios;

import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_ISSUED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.ORDER_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.OrdersApi;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SDD S.4, RF2: processes killed at the worst moments, and two engines on the same queues. The roles are split with
 * S.1's {@code <domain>.engine.enabled} / {@code <domain>.worker.enabled}; the exact kill points are the Billing chaos
 * flags of 8b; everything else is a {@code kill -9} from outside ({@link App#kill()}).
 */
class ChaosIT extends ScenarioBase {

    private static final String HALT = "CHAOS halt at";
    private static final Map<String, String> ENGINE_ONLY = Map.of("billing.worker.enabled", "false");
    private static final Map<String, String> WORKER_ONLY = Map.of("billing.engine.enabled", "false",
            "quarkus.http.port", "8085");

    private static String id(String scenario) {
        return "chaos-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** a. The Billing worker dies in the middle of render-invoice: the task is redelivered, one invoice, one e-mail. */
    @Test
    void a_billing_worker_killed_inside_render_invoice() {
        App ordersApp = start("a-orders", Module.ORDERS, Map.of());
        start("a-billing-engine", Module.BILLING, ENGINE_ONLY);
        App worker1 = start("a-billing-worker-1", Module.BILLING,
                with(WORKER_ONLY, "billing.chaos.halt-in-task", "render-invoice"));
        String orderId = id("a");
        orders.post(ordersApp.port(), OrdersApi.order(orderId, 41));
        note("POST /orders %s", orderId);

        String halt = worker1.awaitLine(l -> l.contains(HALT), FLOW);
        int exit = worker1.awaitExit(Duration.ofSeconds(30));
        String rendered1 = worker1.awaitLine(l -> l.contains("Rendered invoice"), Duration.ofSeconds(1));
        note("worker 1: %s", message(rendered1));
        note("worker 1 halted, exit %d: %s", exit, message(halt));
        int[] tasks = leftovers.depth("billing-tasks");
        note("billing-tasks right after the halt: visible %d, in flight %d (the unacknowledged render)", tasks[0], tasks[1]);
        assertThat(exit).isEqualTo(137);
        assertThat(spy.count(orderId, INVOICE_ISSUED)).isZero();

        App worker2 = start("a-billing-worker-2", Module.BILLING, WORKER_ONLY);
        String rendered2 = worker2.awaitLine(l -> l.contains("Rendered invoice"), FLOW);
        note("worker 2 (task redelivered after the visibility timeout): %s", message(rendered2));
        awaitStatus(ordersApp.port(), orderId, "confirmed");
        note("order confirmed");
        spy.settle(Duration.ofSeconds(3));

        assertThat(spy.count(orderId, INVOICE_ISSUED)).isEqualTo(1);
        assertThat(spy.count(orderId, ORDER_CONFIRMED)).isEqualTo(1);
        String invoiceId = spy.first(orderId, INVOICE_ISSUED).data().path("invoiceId").asText();
        assertThat(rendered1).contains(invoiceId);
        assertThat(rendered2).contains(invoiceId);
        assertThat(invoicesOf(orderId)).isEqualTo(1);
        assertConfirmedWithItsDocument(orderId);
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);

        Scenario.evidence("S.4-CA3-a.txt", "S.4 RF2a: Billing worker killed inside render-invoice (after the document"
                + " was written, before the result was sent)\n" + timeline()
                + "  result: 1 invoice-issued (" + invoiceId + "), " + invoicesOf(orderId) + " record in billing-invoices,"
                + " 1 order-confirmed; the same invoice rendered twice (idempotent by orderId)\n"
                + "  MailHog:\n" + mails(mail.to(OrdersApi.recipient(orderId))) + Leftovers.report(left));
    }

    /** b. The Billing engine dies between the checkpoint and the publication of invoice-issued: the outbox delivers. */
    @Test
    void b_billing_engine_killed_between_checkpoint_and_publish() {
        App ordersApp = start("b-orders", Module.ORDERS, Map.of());
        start("b-billing-worker", Module.BILLING, WORKER_ONLY);
        App engine1 = start("b-billing-engine-1", Module.BILLING,
                with(ENGINE_ONLY, "billing.chaos.halt-before-publish", INVOICE_ISSUED));
        String orderId = id("b");
        orders.post(ordersApp.port(), OrdersApi.order(orderId, 42));
        note("POST /orders %s", orderId);

        String halt = engine1.awaitLine(l -> l.contains(HALT), FLOW);
        int exit = engine1.awaitExit(Duration.ofSeconds(30));
        note("engine 1 halted, exit %d: %s", exit, message(halt));
        Leftovers.Domain billing = leftovers.of("billing");
        note("billing-outbox right after the halt: %d pending fact(s); billing-instances non-terminal: %s",
                billing.outbox(), billing.nonTerminal());
        spy.settle(Duration.ofSeconds(3));
        note("spy: %d invoice-issued for the order while the engine is down", spy.count(orderId, INVOICE_ISSUED));
        assertThat(exit).isEqualTo(137);
        assertThat(billing.outbox()).isEqualTo(1);
        assertThat(spy.count(orderId, INVOICE_ISSUED)).isZero();

        App engine2 = start("b-billing-engine-2", Module.BILLING, ENGINE_ONLY);
        spy.untilEvents("invoice-issued from the outbox", ev -> spy.count(orderId, INVOICE_ISSUED) == 1, FLOW);
        note("invoice-issued published by engine 2's outbox relay (sweep every 30 s, grace 30 s)");
        awaitStatus(ordersApp.port(), orderId, "confirmed");
        note("order confirmed");
        spy.settle(Duration.ofSeconds(3));

        assertThat(spy.count(orderId, INVOICE_ISSUED)).isEqualTo(1);
        assertThat(spy.count(orderId, ORDER_CONFIRMED)).isEqualTo(1);
        assertThat(engine2.lines()).noneMatch(l -> l.contains(HALT));
        assertConfirmedWithItsDocument(orderId);
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);

        Scenario.evidence("S.4-CA3-b.txt", "S.4 RF2b: Billing engine killed between the checkpoint and the publication"
                + " of invoice-issued\n" + timeline()
                + "  result: the fact left in billing-outbox was published once by the restarted engine; 1 order-confirmed\n"
                + "  MailHog:\n" + mails(mail.to(OrdersApi.recipient(orderId))) + Leftovers.report(left));
    }

    /** c. The Orders engine dies while the order waits on its listen; restarted, it resumes when the invoice arrives. */
    @Test
    void c_orders_engine_killed_while_waiting_on_the_listen() {
        Map<String, String> ordersProps = Map.of("orders.invoice.timeout", "PT5M");
        App orders1 = start("c-orders-1", Module.ORDERS, ordersProps);
        String orderId = id("c");
        String instanceId = orders.post(orders1.port(), OrdersApi.order(orderId, 43)).path("instanceId").asText();
        note("POST /orders %s -> instance %s (Billing not running)", orderId, instanceId);
        spy.until("the listen's wait saved", () -> waitsOf("orders", instanceId) > 0, FLOW);
        note("orders-waits: %d wait(s) of the instance; GET says %s", waitsOf("orders", instanceId),
                orders.status(orders1.port(), orderId));

        orders1.kill();
        note("orders 1 killed (kill -9)");
        start("c-billing", Module.BILLING, Map.of());
        spy.untilEvents("invoice-issued", ev -> spy.count(orderId, INVOICE_ISSUED) == 1, FLOW);
        spy.until("the invoice waiting on orders-domain-events", () -> leftovers.depth("orders-domain-events")[0] >= 1, FLOW);
        int[] waiting = leftovers.depth("orders-domain-events");
        note("invoice-issued on the bus; orders-domain-events with Orders down: visible %d, in flight %d", waiting[0], waiting[1]);

        App orders2 = start("c-orders-2", Module.ORDERS, ordersProps);
        awaitStatus(orders2.port(), orderId, "confirmed");
        note("orders 2: order confirmed");
        spy.settle(Duration.ofSeconds(3));

        assertThat(spy.count(orderId, ORDER_CONFIRMED)).isEqualTo(1);
        assertConfirmedWithItsDocument(orderId);
        assertThat(mail.to(OrdersApi.recipient(orderId))).hasSize(1);
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);

        Scenario.evidence("S.4-CA3-c.txt", "S.4 RF2c: Orders engine killed while the order waited on its listen\n"
                + timeline() + "  result: the restarted engine consumed the invoice and resumed the instance; 1 confirmation\n"
                + "  MailHog:\n" + mails(mail.to(OrdersApi.recipient(orderId))) + Leftovers.report(left));
    }

    /** d. Two Orders engines on the same tables and queues: each order advances once and mails once. */
    @Test
    void d_two_orders_replicas_on_the_same_tables_and_queues() {
        App replicaA = start("d-orders-a", Module.ORDERS, Map.of());
        App replicaB = start("d-orders-b", Module.ORDERS, Map.of("quarkus.http.port", "8083"));
        start("d-billing", Module.BILLING, Map.of());
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String orderId = "chaos-d-" + run + "-" + i;
            ids.add(orderId);
            orders.post(i % 2 == 1 ? replicaA.port() : replicaB.port(), OrdersApi.order(orderId, 50 + i));
        }
        note("20 POST /orders, alternating :%d and :%d", replicaA.port(), replicaB.port());

        // The first five invoices are delivered twice: the same event (source, id) published again on the bus.
        List<String> duplicated = ids.subList(0, 5);
        for (String orderId : duplicated) {
            spy.untilEvents("invoice-issued of " + orderId, ev -> spy.count(orderId, INVOICE_ISSUED) >= 1, FLOW);
            Spy.Event issued = spy.first(orderId, INVOICE_ISSUED);
            floci.eventBridge.putEvents(r -> r.entries(e -> e.eventBusName("sandbox-domain").source(issued.source())
                    .detailType(issued.type()).detail(issued.json())));
        }
        note("invoice-issued of %s published a second time (same source and id)", duplicated);

        spy.untilEvents("20 order-confirmed", ev -> ids.stream().allMatch(id -> spy.count(id, ORDER_CONFIRMED) == 1), FLOW);
        spy.until("20 confirmation e-mails", () -> ids.stream().allMatch(id -> mail.to(OrdersApi.recipient(id)).size() == 1), FLOW);
        note("20 orders confirmed");
        Map<String, Leftovers.Domain> left = assertNothingLeft(duplicated.size());
        spy.settle(Duration.ofSeconds(3));

        for (String orderId : ids) {
            assertThat(spy.count(orderId, ORDER_CONFIRMED)).as(orderId).isEqualTo(1);
            assertThat(spy.count(orderId, OrdersApi.INVOICE_REQUESTED)).as(orderId).isEqualTo(1);
            assertConfirmedWithItsDocument(orderId);
        }
        for (String orderId : duplicated) {
            assertThat(spy.count(orderId, INVOICE_ISSUED)).as(orderId).isEqualTo(2);
        }
        Set<String> byA = instancesIn(replicaA);
        Set<String> byB = instancesIn(replicaB);
        note("instances that logged activity on replica a: %d, on replica b: %d, on both: %d", byA.size(), byB.size(),
                byA.stream().filter(byB::contains).count());
        assertThat(byA).as("replica a did work").isNotEmpty();
        assertThat(byB).as("replica b did work").isNotEmpty();

        Scenario.evidence("S.4-CA3-d.txt", "S.4 RF2d: two Orders engines (+ workers) over the same orders-* tables and"
                + " queues, 20 orders, 5 invoices delivered twice\n" + timeline()
                + "  result: 20 order-confirmed (1 each), 20 confirmation e-mails (1 each, own invoice attached);"
                + " of the 5 duplicates, " + left.get("orders").inbox() + " kept in orders-inbox (arrived after the resume),"
                + " the others dropped as already consumed (raced the original while the wait existed)\n"
                + "  MailHog: " + ids.stream().mapToInt(id -> mail.to(OrdersApi.recipient(id)).size()).sum() + " e-mails to the 20 recipients\n"
                + Leftovers.report(left));
    }

    /** e. Billing down longer than Orders' timeout, 10 orders: delay notices, then confirmations when it is back. */
    @Test
    void e_billing_down_past_the_orders_timeout_with_ten_orders() {
        App ordersApp = start("e-orders", Module.ORDERS, Map.of("orders.invoice.timeout", "PT20S"));
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String orderId = "chaos-e-" + run + "-" + i;
            ids.add(orderId);
            orders.post(ordersApp.port(), OrdersApi.order(orderId, 60 + i));
        }
        note("10 POST /orders with Billing down (orders.invoice.timeout=PT20S)");
        spy.until("10 delay notices", () -> ids.stream().allMatch(id -> mail.to(OrdersApi.recipient(id)).size() == 1), FLOW);
        spy.until("10 orders invoice-delayed", () -> ids.stream()
                .allMatch(id -> "invoice-delayed".equals(orders.status(ordersApp.port(), id))), FLOW);
        spy.until("10 late-invoice schedules", () -> leftovers.of("orders").oneTimeSchedules().size() == 10, FLOW);
        note("10 delay notices; 10 orders invoice-delayed; orders-timers one-time schedules: %d",
                leftovers.of("orders").oneTimeSchedules().size());
        int[] waiting = leftovers.depth("billing-domain-events");
        note("billing-domain-events while Billing is down: visible %d", waiting[0]);

        start("e-billing", Module.BILLING, Map.of());
        spy.untilEvents("10 order-confirmed", ev -> ids.stream().allMatch(id -> spy.count(id, ORDER_CONFIRMED) == 1), FLOW);
        spy.until("10 confirmations", () -> ids.stream().allMatch(id -> mail.to(OrdersApi.recipient(id)).size() == 2), FLOW);
        note("Billing back: 10 orders confirmed");
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);
        spy.settle(Duration.ofSeconds(3));

        for (String orderId : ids) {
            assertThat(orders.status(ordersApp.port(), orderId)).isEqualTo("confirmed");
            assertThat(mail.to(OrdersApi.recipient(orderId))).extracting(m -> m.subject())
                    .containsExactlyInAnyOrder("Order " + orderId + ": invoicing is delayed",
                            "Order " + orderId + " confirmed: invoice " + spy.first(orderId, INVOICE_ISSUED).data().path("invoiceId").asText());
            assertConfirmedWithItsDocument(orderId);
        }
        Scenario.evidence("S.4-CA3-e.txt", "S.4 RF2e: Billing down past Orders' listen timeout, 10 orders\n" + timeline()
                + "  result: 10 delay notices, then 10 confirmations (second listen, S.3 8c); the 10 one-time 24 h"
                + " schedules deleted\n  MailHog (" + ids.get(0) + "):\n" + mails(mail.to(OrdersApi.recipient(ids.get(0))))
                + "  MailHog total for the 10 recipients: " + ids.stream().mapToInt(id -> mail.to(OrdersApi.recipient(id)).size()).sum()
                + "\n" + Leftovers.report(left));
    }

    private int invoicesOf(String orderId) {
        return floci.ddb.scan(r -> r.tableName("billing-invoices").consistentRead(true).filterExpression("orderId = :o")
                .expressionAttributeValues(Map.of(":o", AttributeValue.fromS(orderId)))).count();
    }

    private static final Pattern INSTANCE = Pattern.compile("\"stint\\.instanceId\":\"([^\"]+)\"");

    /** The instances whose engine or worker activity this replica logged (Stint's log context, stint.instanceId). */
    private static Set<String> instancesIn(App app) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : app.lines()) {
            Matcher m = INSTANCE.matcher(line);
            if (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> props = new java.util.LinkedHashMap<>(base);
        props.put(key, value);
        return props;
    }
}
