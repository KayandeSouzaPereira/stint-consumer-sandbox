package io.stintflow.sandbox.scenarios;

import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_ISSUED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_REQUESTED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.ORDER_CONFIRMED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.OrdersApi;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.4, RF4: the happy path of S.3 CA1 with both domains as native images ({@code infra/gate.sh --native}: built
 * with {@code -Dnative -Dquarkus.native.container-build=true}, run in containers on the compose network, 8f). Skipped
 * unless {@code -Dscenarios.apps=native}.
 */
class NativeIT extends ScenarioBase {

    @Test
    void the_happy_path_runs_on_both_native_images() {
        assumeTrue(Scenario.nativeApps(), "native images only (-Dscenarios.apps=native)");
        App ordersApp = start("native-orders", Module.ORDERS, Map.of());
        App billing = start("native-billing", Module.BILLING, Map.of());
        String ordersStarted = ordersApp.awaitLine(l -> l.contains("started in"), Duration.ofSeconds(1));
        String billingStarted = billing.awaitLine(l -> l.contains("started in"), Duration.ofSeconds(1));
        assertThat(ordersStarted).contains("native");
        assertThat(billingStarted).contains("native");
        note("sandbox-orders: %s", message(ordersStarted));
        note("sandbox-billing: %s", message(billingStarted));

        String orderId = "native-" + UUID.randomUUID().toString().substring(0, 8);
        orders.post(ordersApp.port(), OrdersApi.order(orderId, 88));
        awaitStatus(ordersApp.port(), orderId, "confirmed");
        spy.untilEvents("order-confirmed", ev -> spy.count(orderId, ORDER_CONFIRMED) == 1, FLOW);
        spy.settle(Duration.ofSeconds(3));
        note("order %s confirmed", orderId);

        assertThat(spy.of(orderId)).extracting(Spy.Event::type)
                .containsExactlyInAnyOrder(ORDER_PLACED, INVOICE_REQUESTED, INVOICE_ISSUED, ORDER_CONFIRMED);
        assertThat(spy.of(orderId)).extracting(Spy.Event::chainId).containsOnly(OrdersApi.chainId(orderId));
        assertConfirmedWithItsDocument(orderId);
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);

        Scenario.evidence("S.4-CA5.txt", "S.4 RF4: S.3 CA1's happy path on both native images\n" + timeline()
                + "  spy, one chain " + OrdersApi.chainId(orderId) + ": " + spy.of(orderId).stream().map(Spy.Event::type).toList()
                + "\n  MailHog:\n" + mails(mail.to(OrdersApi.recipient(orderId))) + Leftovers.report(left));
    }
}
