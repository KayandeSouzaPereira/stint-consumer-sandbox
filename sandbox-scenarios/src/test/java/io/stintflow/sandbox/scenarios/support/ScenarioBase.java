package io.stintflow.sandbox.scenarios.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.stintflow.sandbox.scenarios.support.App.Module;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SDD S.4: a scenario's lifecycle — floci and MailHog reset before it (8e), its processes stopped after it — and the
 * timeline its evidence is written from.
 */
public abstract class ScenarioBase {

    protected static final Duration FLOW = Duration.ofSeconds(180);
    protected static final String ISSUED = OrdersApi.INVOICE_ISSUED;

    protected final Floci floci = new Floci();
    protected final Spy spy = new Spy(floci.sqs);
    protected final MailHog mail = new MailHog();
    protected final OrdersApi orders = new OrdersApi();
    protected final Leftovers leftovers = new Leftovers(floci);
    protected final List<App> apps = new ArrayList<>();
    private final StringBuilder timeline = new StringBuilder();
    private Instant t0;

    @BeforeEach
    void resetFloci() {
        Scenario.reset();
        t0 = Instant.now();
    }

    @AfterEach
    void stopApps() {
        apps.forEach(App::close);
        floci.close();
    }

    protected App start(String name, Module module, Map<String, String> props) {
        App app = App.start(name, module, props);
        apps.add(app);
        note("started %s (%s, pid %d, :%d) %s", name, module, app.pid(), app.port(), props.isEmpty() ? "" : props);
        return app;
    }

    /** A line of the scenario's timeline, stamped with the seconds since it began. */
    protected void note(String format, Object... args) {
        timeline.append(String.format(java.util.Locale.ROOT, "  t+%6.1fs  ", Duration.between(t0, Instant.now()).toMillis() / 1000.0))
                .append(String.format(format, args)).append('\n');
    }

    protected String timeline() {
        return timeline.toString();
    }

    protected void awaitStatus(int port, String orderId, String status) {
        spy.until(orderId + " " + status, () -> status.equals(orders.status(port, orderId)), FLOW);
    }

    /** The wait an instance registered (orders-waits / billing-waits, GSI byInstance-index). */
    protected int waitsOf(String domain, String instanceId) {
        return floci.ddb.query(r -> r.tableName(domain + "-waits").indexName("byInstance-index")
                .keyConditionExpression("instanceId = :i")
                .expressionAttributeValues(Map.of(":i", AttributeValue.fromS(instanceId)))).count();
    }

    /** The confirmation e-mail of an order carries, byte for byte, the document its invoice cites. */
    protected void assertConfirmedWithItsDocument(String orderId) {
        Spy.Event issued = spy.first(orderId, ISSUED);
        URI document = URI.create(issued.data().path("invoiceDocument").asText());
        byte[] published = floci.s3.getObjectAsBytes(r -> r.bucket(document.getHost()).key(document.getPath().substring(1)))
                .asByteArray();
        String invoiceId = issued.data().path("invoiceId").asText();
        assertThat(mail.to(OrdersApi.recipient(orderId))).filteredOn(m -> m.subject().contains("confirmed"))
                .singleElement().satisfies(m -> {
                    assertThat(m.subject()).isEqualTo("Order " + orderId + " confirmed: invoice " + invoiceId);
                    assertThat(m.attachments()).singleElement().satisfies(a -> {
                        assertThat(a.name()).isEqualTo("invoice-" + invoiceId + ".html");
                        assertThat(a.content()).isEqualTo(published);
                        assertThat(new String(a.content(), java.nio.charset.StandardCharsets.UTF_8)).contains(orderId);
                    });
                });
    }

    /**
     * 8e: nothing left once the scenario is quiet. {@code ordersInboxAtMost} is the number of duplicates sent on
     * purpose: a duplicate that arrives after the resume is kept in the inbox, one that races the original while
     * the wait still exists loses the conditional save and is dropped as already consumed (Stint SDD 2.3).
     */
    protected Map<String, Leftovers.Domain> assertNothingLeft(int ordersInboxAtMost) {
        Map<String, Leftovers.Domain> all = leftovers.awaitQuiet(spy, FLOW);
        for (Leftovers.Domain d : all.values()) {
            assertThat(d.waits()).as(d.domain() + " waits").isZero();
            assertThat(d.outbox()).as(d.domain() + " outbox").isZero();
            assertThat(d.nonTerminal()).as(d.domain() + " non-terminal instances").isEmpty();
            assertThat(d.dlqMessages()).as(d.domain() + " DLQ messages").isZero();
            assertThat(d.oneTimeSchedules()).as(d.domain() + " one-time schedules").isEmpty();
            assertThat(d.inbox()).as(d.domain() + " inbox").isBetween(0, "orders".equals(d.domain()) ? ordersInboxAtMost : 0);
        }
        return all;
    }

    protected static String mails(List<MailHog.Mail> mails) {
        StringBuilder out = new StringBuilder();
        mails.forEach(m -> out.append("    ").append(m.to()).append(" | ").append(m.subject()).append(" | ")
                .append(m.attachments().stream().map(a -> a.name() + " (" + a.content().length + " bytes)").toList())
                .append('\n'));
        return out.toString();
    }

    protected static String message(String line) {
        try {
            return Spy.JSON.readTree(line).path("message").asText(line);
        } catch (Exception e) {
            return line;
        }
    }
}
