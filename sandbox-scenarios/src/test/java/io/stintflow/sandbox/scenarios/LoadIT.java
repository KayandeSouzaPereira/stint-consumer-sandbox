package io.stintflow.sandbox.scenarios;

import static io.stintflow.sandbox.scenarios.support.OrdersApi.INVOICE_ISSUED;
import static io.stintflow.sandbox.scenarios.support.OrdersApi.ORDER_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.OrdersApi;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import software.amazon.awssdk.services.dynamodb.model.Select;

/**
 * SDD S.4, RF3: N simultaneous orders (default 50, {@code -Dscenarios.orders}), released together by a barrier. One in
 * ten is a {@code fail503-} order (Billing's 1 s retry, S.4 8a, under load) and one in ten a {@code slow-} one. The
 * end-to-end latency (POST to order-confirmed) is recorded only as an observation.
 */
class LoadIT extends ScenarioBase {

    @Test
    void n_simultaneous_orders_are_all_invoiced_and_confirmed_with_nothing_left_over() throws Exception {
        int n = Scenario.orders();
        App ordersApp = start("load-orders", Module.ORDERS, Map.of());
        start("load-billing", Module.BILLING, Map.of());
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String prefix = i % 10 == 1 ? "fail503-" : i % 10 == 2 ? "slow-" : "";
            ids.add(prefix + "load-" + run + "-" + i);
        }
        Map<String, Instant> postedAt = new ConcurrentHashMap<>();
        CyclicBarrier together = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<CompletableFuture<Void>> posts = ids.stream().map(id -> CompletableFuture.runAsync(() -> {
                try {
                    together.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                postedAt.put(id, Instant.now());
                orders.post(ordersApp.port(), OrdersApi.order(id, 10 + ids.indexOf(id)));
            }, pool)).toList();
            CompletableFuture.allOf(posts.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        note("%d POST /orders released together by a barrier (%d fail503-, %d slow-)", n,
                ids.stream().filter(id -> id.startsWith("fail503-")).count(), ids.stream().filter(id -> id.startsWith("slow-")).count());

        Duration within = Duration.ofSeconds(120 + 6L * n);
        spy.untilEvents(n + " order-confirmed", ev -> ids.stream().allMatch(id -> spy.count(id, ORDER_CONFIRMED) >= 1), within);
        note("%d order-confirmed on the spy", n);
        spy.until(n + " confirmation e-mails", () -> ids.stream().allMatch(id -> mail.to(OrdersApi.recipient(id)).size() >= 1), within);
        note("%d confirmation e-mails in MailHog", n);
        Map<String, Leftovers.Domain> left = assertNothingLeft(0);
        spy.settle(Duration.ofSeconds(3));

        for (String id : ids) {
            assertThat(spy.count(id, INVOICE_ISSUED)).as(id).isEqualTo(1);
            assertThat(spy.count(id, ORDER_CONFIRMED)).as(id).isEqualTo(1);
            assertConfirmedWithItsDocument(id);
        }
        Set<String> chains = ids.stream().map(id -> spy.first(id, ORDER_CONFIRMED).chainId()).collect(Collectors.toSet());
        assertThat(chains).hasSize(n);
        ids.forEach(id -> assertThat(spy.first(id, ORDER_CONFIRMED).chainId()).isEqualTo(OrdersApi.chainId(id)));
        int invoices = floci.ddb.scan(r -> r.tableName("billing-invoices").select(Select.COUNT).consistentRead(true)).count();
        assertThat(invoices).isEqualTo(n);
        Set<String> invoiceIds = ids.stream().map(id -> spy.first(id, INVOICE_ISSUED).data().path("invoiceId").asText())
                .collect(Collectors.toSet());
        assertThat(invoiceIds).hasSize(n);

        List<Long> latencies = ids.stream().map(id -> Duration.between(postedAt.get(id),
                spy.first(id, ORDER_CONFIRMED).time()).toMillis()).sorted().toList();
        String observation = String.format(java.util.Locale.ROOT,
                "  latency POST -> order-confirmed (observation only, no target): p50 %.1f s, p95 %.1f s, max %.1f s%n",
                percentile(latencies, 50) / 1000.0, percentile(latencies, 95) / 1000.0, latencies.getLast() / 1000.0);
        List<Long> plain = ids.stream().filter(id -> !id.startsWith("fail503-") && !id.startsWith("slow-"))
                .map(id -> Duration.between(postedAt.get(id), spy.first(id, ORDER_CONFIRMED).time()).toMillis()).sorted().toList();
        observation += String.format(java.util.Locale.ROOT, "  the same without the fail503-/slow- orders: p50 %.1f s, p95 %.1f s%n",
                percentile(plain, 50) / 1000.0, percentile(plain, 95) / 1000.0);

        Scenario.evidence("S.4-CA4.txt", "S.4 RF3: load, N = " + n + " simultaneous orders\n" + timeline()
                + "  counts: " + invoices + " invoices in billing-invoices (" + invoiceIds.size() + " distinct ids), "
                + ids.stream().mapToLong(id -> spy.count(id, INVOICE_ISSUED)).sum() + " invoice-issued, "
                + ids.stream().mapToLong(id -> spy.count(id, ORDER_CONFIRMED)).sum() + " order-confirmed, "
                + ids.stream().mapToInt(id -> mail.to(OrdersApi.recipient(id)).size()).sum() + " confirmation e-mails, "
                + chains.size() + " distinct chains\n"
                + "  every e-mail carries its own order's invoice, byte for byte the object its invoice-issued cites\n"
                + observation + Leftovers.report(left));
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
