package io.stintflow.sandbox.scenarios;

import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_ISSUED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_REQUESTED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.ORDER_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Floci;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.MailHog;
import io.stintflow.sandbox.scenarios.support.OrdersApi;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse;

/**
 * SDD S.4, RF1: the scripted CAs of S.1-S.3 that need processes started, stopped or restarted, automated so the whole
 * regression runs in one command ({@code infra/gate.sh}). The rest of RF1 (S.2 CA1-CA5, S.3 CA1, CA2, CA4-CA7) are
 * the domains' own floci tests, which gate.sh runs before this class.
 */
class RegressionIT {

    private static final Duration FLOW = Duration.ofSeconds(120);

    final Floci floci = new Floci();
    final Spy spy = new Spy(floci.sqs);
    final MailHog mail = new MailHog();
    final OrdersApi orders = new OrdersApi();
    final List<App> apps = new ArrayList<>();
    final String run = System.getProperty("scenarios.run", "1");

    @BeforeEach
    void reset() {
        Scenario.reset();
    }

    @AfterEach
    void stop() {
        apps.forEach(App::close);
        floci.close();
    }

    private App start(String name, Module module, Map<String, String> props) {
        App app = App.start(name + "-run" + run, module, props);
        apps.add(app);
        return app;
    }

    /** S.1 CA2: the Phase 1 reports, both definitions, mailed with final.csv attached. */
    @Test
    void s1_ca2_both_phase1_reports_are_mailed_with_their_csv() throws Exception {
        App reports = start("s1-ca2-reports", Module.REPORTS, Map.of());
        String recipient = "s1-ca2-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        ObjectNode body = Spy.JSON.createObjectNode().put("reportQuery", "monthly sales").put("outputFormat", "csv")
                .put("recipient", recipient).put("subject", "Monthly sales report");
        HttpClient http = HttpClient.newHttpClient();
        List<String> outputs = new ArrayList<>();
        for (String path : List.of("/reports", "/reports/secondary")) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + reports.port() + path))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
            assertThat(Spy.JSON.readTree(response.body()).path("rows").asInt()).as(path).isEqualTo(25);
            outputs.add(path + " -> 200, rows=" + Spy.JSON.readTree(response.body()).path("rows").asInt());
        }
        spy.until("two report e-mails to " + recipient, () -> mail.to(recipient).size() == 2, FLOW);
        List<MailHog.Mail> mails = mail.to(recipient);
        assertThat(mails).extracting(MailHog.Mail::subject)
                .containsExactlyInAnyOrder("Monthly sales report", "URGENT: Monthly sales report");
        assertThat(mails).allSatisfy(m -> assertThat(m.attachments()).extracting(MailHog.Attachment::name)
                .containsExactly("final.csv"));

        StringBuilder e = new StringBuilder("S.1 CA2 (run " + run + "): sandbox-reports as a process (" + reports.log().getFileName() + ")\n");
        outputs.forEach(o -> e.append("  ").append(o).append('\n'));
        mails.forEach(m -> e.append("  mail to ").append(m.to()).append(" | ").append(m.subject()).append(" | ")
                .append(m.attachments().stream().map(a -> a.name() + " (" + a.content().length + " bytes)").toList()).append('\n'));
        Scenario.evidence("S.4-CA2-s1-ca2-run" + run + ".txt", e.toString());
    }

    /** S.2 CA6: with Billing down, the requests wait on its queue; when it is back, every invoice is issued. */
    @Test
    void s2_ca6_requests_wait_while_billing_is_down_and_are_issued_when_it_returns() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = List.of("s2-ca6-" + tag + "-1", "s2-ca6-" + tag + "-2", "slow-s2-ca6-" + tag + "-3");
        for (String orderId : ids) {
            publishRequest(orderId);
        }
        Leftovers leftovers = new Leftovers(floci);
        spy.until("3 requests waiting on billing-domain-events",
                () -> leftovers.depth("billing-domain-events")[0] == 3, FLOW);
        int[] waiting = leftovers.depth("billing-domain-events");

        Instant up = Instant.now();
        App billing = start("s2-ca6-billing", Module.BILLING, Map.of());
        spy.untilEvents("3 invoice-issued", ev -> ids.stream().allMatch(id -> spy.count(id, INVOICE_ISSUED) == 1), FLOW);
        Duration took = Duration.between(up, Instant.now());
        spy.until("billing-domain-events empty", () -> {
            int[] d = leftovers.depth("billing-domain-events");
            return d[0] == 0 && d[1] == 0;
        }, FLOW);
        spy.settle(Duration.ofSeconds(3));
        ids.forEach(id -> assertThat(spy.count(id, INVOICE_ISSUED)).as(id).isEqualTo(1));

        StringBuilder e = new StringBuilder("S.2 CA6 (run " + run + "): Billing down, 3 invoice-requested published on sandbox-domain\n");
        e.append("  billing-domain-events while Billing is down: visible ").append(waiting[0]).append(", in flight ")
                .append(waiting[1]).append('\n');
        e.append("  Billing started (").append(billing.log().getFileName()).append("): 3 invoice-issued within ")
                .append(Scenario.seconds(took)).append(" of the start\n");
        ids.forEach(id -> e.append("  ").append(id).append(" -> invoice ").append(spy.first(id, INVOICE_ISSUED).data().path("invoiceId").asText()).append('\n'));
        int[] after = leftovers.depth("billing-domain-events");
        e.append("  billing-domain-events after: visible ").append(after[0]).append(", in flight ").append(after[1]).append('\n');
        Scenario.evidence("S.4-CA2-s2-ca6-run" + run + ".txt", e.toString());
    }

    /**
     * S.3 CA3: Billing down past Orders' invoice timeout (PT20S here; S.4 8a): delay notice, a one-time 24 h schedule
     * for the late answer; Billing back: confirmation, schedule deleted; a simulated late fire of it is ignored.
     */
    @Test
    void s3_ca3_billing_down_past_the_timeout_delays_then_confirms_and_a_late_fire_is_ignored() {
        App ordersApp = start("s3-ca3-orders", Module.ORDERS, Map.of("orders.invoice.timeout", "PT20S"));
        String orderId = "s3-ca3-" + UUID.randomUUID().toString().substring(0, 8);
        String recipient = OrdersApi.recipient(orderId);
        orders.post(ordersApp.port(), OrdersApi.order(orderId, 31));

        spy.until("the delay notice", () -> mail.to(recipient).size() == 1, FLOW);
        assertThat(mail.to(recipient)).singleElement().extracting(MailHog.Mail::subject)
                .isEqualTo("Order " + orderId + ": invoicing is delayed");
        spy.until("status invoice-delayed", () -> "invoice-delayed".equals(orders.status(ordersApp.port(), orderId)), FLOW);
        spy.until("the late-invoice schedule", () -> !new Leftovers(floci).of("orders").oneTimeSchedules().isEmpty(), FLOW);
        String scheduleName = new Leftovers(floci).of("orders").oneTimeSchedules().getFirst();
        GetScheduleResponse schedule = floci.scheduler.getSchedule(r -> r.groupName("orders-timers").name(scheduleName));
        String timerJson = schedule.target().input();

        App billing = start("s3-ca3-billing", Module.BILLING, Map.of());
        spy.until("the confirmation", () -> mail.to(recipient).size() == 2, FLOW);
        spy.until("status confirmed", () -> "confirmed".equals(orders.status(ordersApp.port(), orderId)), FLOW);
        spy.until("the schedule deleted", () -> new Leftovers(floci).of("orders").oneTimeSchedules().isEmpty(), FLOW);

        // The cancelled timer fires anyway (as if its deletion had raced its firing): Stint must ignore it.
        floci.sqs.sendMessage(r -> r.queueUrl(Floci.queueUrl("orders-timers")).messageBody(timerJson));
        JsonNode timer = read(timerJson);
        String ignored = ordersApp.awaitLine(l -> l.contains("decision=obsolete") && l.contains(timer.path("timerId").asText()), FLOW);
        spy.settle(Duration.ofSeconds(3));
        assertThat(orders.status(ordersApp.port(), orderId)).isEqualTo("confirmed");
        assertThat(mail.to(recipient)).hasSize(2);
        assertThat(spy.of(orderId)).extracting(Spy.Event::chainId).containsOnly(OrdersApi.chainId(orderId));
        assertThat(spy.count(orderId, ORDER_CONFIRMED)).isEqualTo(1);

        StringBuilder e = new StringBuilder("S.3 CA3 (run " + run + "): orders.invoice.timeout=PT20S, Billing down\n");
        e.append("  mails to ").append(recipient).append(": ").append(mail.to(recipient).stream().map(MailHog.Mail::subject).toList()).append('\n');
        e.append("  one-time schedule while delayed: orders-timers/").append(scheduleName).append(" at ")
                .append(schedule.scheduleExpression()).append(", input ").append(timerJson).append('\n');
        e.append("  after Billing came back: status confirmed, orders-timers schedules ")
                .append(new Leftovers(floci).of("orders").oneTimeSchedules()).append('\n');
        e.append("  simulated late fire -> ").append(message(ignored)).append('\n');
        e.append("  spy, one chain ").append(OrdersApi.chainId(orderId)).append(": ")
                .append(spy.of(orderId).stream().map(Spy.Event::type).toList()).append('\n');
        e.append("  logs: ").append(ordersApp.log().getFileName()).append(", ").append(billing.log().getFileName()).append('\n');
        Scenario.evidence("S.4-CA2-s3-ca3-run" + run + ".txt", e.toString());
    }

    private void publishRequest(String orderId) {
        ObjectNode ce = Spy.JSON.createObjectNode();
        ce.put("specversion", "1.0");
        ce.put("id", "req-" + orderId);
        ce.put("source", "https://sandbox.stintflow.io/orders");
        ce.put("type", INVOICE_REQUESTED);
        ce.put("time", Instant.now().toString());
        ce.put("datacontenttype", "application/json");
        ce.put("chainid", OrdersApi.chainId(orderId));
        ce.putObject("data").put("orderId", orderId).put("customerId", "c-scenario").put("amount", 40).put("currency", "BRL");
        floci.eventBridge.putEvents(r -> r.entries(en -> en.eventBusName("sandbox-domain")
                .source(ce.get("source").asText()).detailType(INVOICE_REQUESTED).detail(ce.toString())));
    }

    static JsonNode read(String json) {
        try {
            return Spy.JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The "message" of a JSON log line (the whole line if it isn't JSON). */
    static String message(String line) {
        try {
            return Spy.JSON.readTree(line).path("message").asText(line);
        } catch (Exception e) {
            return line;
        }
    }
}
