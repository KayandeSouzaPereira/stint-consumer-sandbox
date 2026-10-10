package io.stintflow.sandbox.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.model.CallRemoteNode;
import io.stintflow.core.model.TaskNode;
import io.stintflow.core.model.TryNode;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.sandbox.billing.aws.S3InvoiceDocuments;

/**
 * SDD S.2, RF7/CA7 (pattern of Stint's {@code ArchiveReportIndependenceTest}): Billing knows the Orders domain only
 * through {@code contracts/events.md}. Besides the build's {@code bannedDependencies} rule, nothing in this module's
 * sources or resources may name that domain: the contract's types, fields ({@code orderId}) and nothing else. Removing
 * a banned word below would hide a coupling, not fix it.
 */
class BillingIndependenceTest {

    /** What would betray knowledge of the other domain (compared case-insensitively). */
    private static final List<String> FORBIDDEN = List.of("orders", "order-confirmed", "orderconfirmed");

    @Test
    void no_source_or_resource_names_the_orders_domain() throws IOException {
        Path module = Path.of("").toAbsolutePath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(module.resolve("src/main"))) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                assertThat(text).as("%s must not mention %s", module.relativize(file), forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void definitions_start_only_on_the_contract_type_and_call_only_billing_routes() {
        Map<String, WorkflowDefinition> byName = new DslLoader()
                .loadClasspath("stint/workflows/*.yaml", LoadOptions.STRICT).stream()
                .collect(Collectors.toMap(def -> def.ref().name(), Function.identity()));
        assertThat(byName).containsOnlyKeys("issue-invoice", "billing-summary");

        WorkflowDefinition issue = byName.get("issue-invoice");
        assertThat(issue.ref()).isEqualTo(Billing.ISSUE_INVOICE);
        assertThat(issue.schedule().on()).singleElement().satisfies(filter -> {
            assertThat(filter.type()).isEqualTo(Billing.INVOICE_REQUESTED);
            assertThat(filter.with()).isEmpty(); // not even the publisher's source
        });
        assertThat(byName.get("billing-summary").schedule().cron()).isNotNull();

        List<String> routes = byName.values().stream()
                .flatMap(def -> def.root().tasks().stream())
                .map(BillingIndependenceTest::remoteRoute)
                .filter(route -> route != null)
                .toList();
        assertThat(routes).containsExactlyInAnyOrder(Billing.ROUTE_RENDER_INVOICE, Billing.ROUTE_RECORD_INVOICE,
                Billing.ROUTE_COUNT_INVOICES, Billing.ROUTE_SEND_SUMMARY);
    }

    @Test
    void invoice_documents_are_written_only_under_billings_prefix() {
        // contracts/events.md, "Bucket sandbox-facts": Billing writes billing/... (and its engine facts/billing/...).
        assertThat(S3InvoiceDocuments.key("inv-1")).isEqualTo("billing/invoices/inv-1.html");
        assertThat(S3InvoiceDocuments.PREFIX).startsWith("billing/");
        assertThat(Billing.ISSUE_INVOICE.namespace()).isEqualTo("billing");
        assertThat(Billing.BILLING_SUMMARY.namespace()).isEqualTo("billing");
    }

    private static String remoteRoute(TaskNode task) {
        if (task instanceof TryNode tryNode) {
            return tryNode.body().routingKey();
        }
        return task instanceof CallRemoteNode call ? call.routingKey() : null;
    }
}
