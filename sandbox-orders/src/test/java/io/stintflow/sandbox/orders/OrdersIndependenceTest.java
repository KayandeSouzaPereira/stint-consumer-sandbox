package io.stintflow.sandbox.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.model.CallRemoteNode;
import io.stintflow.core.model.TaskNode;

/**
 * SDD S.3, RF6/RF7/CA9 (pattern of the S.2 CA7 test): Orders knows the other domain only through
 * {@code contracts/events.md}. Its sources and resources may name the contract's invoice types and the invoice
 * document's published location, and nothing else of it: no module, resource, route or test-data prefix. Removing a
 * banned word below would hide a coupling, not fix it.
 */
class OrdersIndependenceTest {

    /** What the contract itself says, and so may appear (compared case-insensitively). */
    private static final List<String> CONTRACT_TERMS = List.of(
            "io.sandbox.billing.invoice-requested.v1", "io.sandbox.billing.invoice-issued.v1",
            "io.sandbox.billing.invoice-rejected.v1", "/billing/invoices/", "https://sandbox.stintflow.io/billing",
            "rejected by billing");
    /** Test data of the other domain (its fault prefixes) and anything else that would betray knowing it. */
    private static final List<String> FORBIDDEN = List.of("fail503", "slow-", "8082");

    @Test
    void no_source_or_resource_knows_the_other_domain_beyond_the_contract() throws IOException {
        Path module = Path.of("").toAbsolutePath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(module.resolve("src/main"))) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            for (String term : CONTRACT_TERMS) {
                text = text.replace(term, "");
            }
            assertThat(text).as("%s names the other domain beyond the contract", module.relativize(file))
                    .doesNotContain("billing");
            for (String forbidden : FORBIDDEN) {
                assertThat(text).as("%s must not mention %s", module.relativize(file), forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void the_definition_starts_on_its_own_entry_event_and_calls_only_orders_routes() {
        WorkflowDefinition def = OrderDefinitions.placeOrder(Duration.ofMinutes(2), Duration.ofHours(24));

        assertThat(def.ref()).isEqualTo(Orders.PLACE_ORDER);
        assertThat(def.schedule().on()).singleElement().satisfies(filter -> {
            assertThat(filter.type()).isEqualTo(Orders.ORDER_PLACED);
            assertThat(filter.with()).isEmpty();
        });
        List<String> routes = def.root().tasks().stream()
                .filter(CallRemoteNode.class::isInstance)
                .map(TaskNode.class::cast)
                .map(task -> ((CallRemoteNode) task).routingKey())
                .toList();
        assertThat(routes).containsExactlyInAnyOrder(Orders.ROUTE_SEND_DELAY_NOTICE, Orders.ROUTE_SEND_CONFIRMATION,
                Orders.ROUTE_SEND_REJECTION);
    }
}
