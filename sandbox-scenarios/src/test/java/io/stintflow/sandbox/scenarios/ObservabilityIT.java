package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.OrdersApi;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.4, RF5 (8g): one order, both domains as processes exporting OTLP to the compose's Jaeger. The trace of each
 * domain is found by its span attribute {@code stint.chainId} (Stint SDD 2.5), and the two are linked both ways:
 * Billing's activation follows from Orders' span that published the request, and Orders' resumed listen follows from
 * Billing's span that published the invoice. The JSON logs of both processes carry the same {@code stint.chainId}.
 */
class ObservabilityIT extends ScenarioBase {

    private final HttpClient http = HttpClient.newHttpClient();

    /** A span as Jaeger's query API returns it, with the service of its process. */
    record SpanRef(String service, String traceId, String spanId, String operation, List<String[]> refs) {
    }

    @Test
    void one_order_is_one_linked_trace_across_both_domains_and_one_chain_in_both_logs() throws Exception {
        App ordersApp = start("obs-orders", Module.ORDERS, Map.of());
        App billing = start("obs-billing", Module.BILLING, Map.of());
        String orderId = "obs-" + UUID.randomUUID().toString().substring(0, 8);
        String chain = OrdersApi.chainId(orderId);
        orders.post(ordersApp.port(), OrdersApi.order(orderId, 77));
        awaitStatus(ordersApp.port(), orderId, "confirmed");
        note("order %s confirmed, chain %s", orderId, chain);

        // Spans are exported in batches: wait (spy long polls between reads) until both services show the chain.
        Map<String, SpanRef> spans = new HashMap<>();
        List<JsonNode> raw = new ArrayList<>();
        spy.until("spans of both services with stint.chainId " + chain, () -> {
            spans.clear();
            raw.clear();
            for (String service : List.of("sandbox-orders", "sandbox-billing")) {
                JsonNode found = traces(service, chain);
                raw.add(found);
                collect(found, spans);
            }
            Set<String> services = new LinkedHashSet<>();
            spans.values().forEach(s -> services.add(s.service()));
            return services.containsAll(List.of("sandbox-orders", "sandbox-billing")) && crossLinks(spans).size() >= 2;
        }, FLOW);

        List<String> links = crossLinks(spans);
        note("Jaeger: %d spans with the chain (%d in sandbox-orders, %d in sandbox-billing)", spans.size(),
                spans.values().stream().filter(s -> s.service().equals("sandbox-orders")).count(),
                spans.values().stream().filter(s -> s.service().equals("sandbox-billing")).count());
        links.forEach(l -> note("link %s", l));
        assertThat(links).anyMatch(l -> l.startsWith("sandbox-billing")).anyMatch(l -> l.startsWith("sandbox-orders"));

        List<String> ordersLogs = chainLines(ordersApp, chain);
        List<String> billingLogs = chainLines(billing, chain);
        assertThat(ordersLogs).isNotEmpty();
        assertThat(billingLogs).isNotEmpty();
        note("JSON log lines with mdc stint.chainId=%s: sandbox-orders %d, sandbox-billing %d", chain, ordersLogs.size(),
                billingLogs.size());

        Files.writeString(Scenario.out().resolve("S.4-CA6-jaeger.json"), Spy.JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(raw), StandardCharsets.UTF_8);
        StringBuilder e = new StringBuilder("S.4 RF5: one order, traces in Jaeger and logs of both processes\n").append(timeline());
        e.append("  Jaeger query: ").append(Scenario.JAEGER).append("/api/traces?service=<service>&tags={\"stint.chainId\":\"")
                .append(chain).append("\"} (raw JSON: S.4-CA6-jaeger.json)\n  spans:\n");
        spans.values().stream().sorted((a, b) -> a.service().compareTo(b.service())).forEach(s -> e.append(String.format(
                "    %-16s %-17s trace %s span %s refs %s%n", s.service(), s.operation(), s.traceId(), s.spanId(),
                s.refs().stream().map(r -> r[0] + ":" + r[1] + "/" + r[2]).toList())));
        e.append("  sandbox-orders log lines (").append(ordersApp.log().getFileName()).append("):\n");
        ordersLogs.forEach(l -> e.append("    ").append(l).append('\n'));
        e.append("  sandbox-billing log lines (").append(billing.log().getFileName()).append("):\n");
        billingLogs.forEach(l -> e.append("    ").append(l).append('\n'));
        Scenario.evidence("S.4-CA6.txt", e.toString());
    }

    private JsonNode traces(String service, String chain) {
        try {
            String tags = URLEncoder.encode("{\"stint.chainId\":\"" + chain + "\"}", StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(Scenario.JAEGER
                    + "/api/traces?service=" + service + "&lookback=1h&limit=50&tags=" + tags)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return Spy.JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Jaeger at " + Scenario.JAEGER + " is not readable", e);
        }
    }

    /** Every span of the traces found, keyed by span id (a trace can hold spans of both services). */
    private static void collect(JsonNode found, Map<String, SpanRef> spans) {
        for (JsonNode trace : found.path("data")) {
            JsonNode processes = trace.path("processes");
            for (JsonNode span : trace.path("spans")) {
                List<String[]> refs = new ArrayList<>();
                for (JsonNode ref : span.path("references")) {
                    refs.add(new String[] {ref.path("refType").asText(), ref.path("traceID").asText(), ref.path("spanID").asText()});
                }
                String service = processes.path(span.path("processID").asText()).path("serviceName").asText();
                spans.put(span.path("spanID").asText(), new SpanRef(service, span.path("traceID").asText(),
                        span.path("spanID").asText(), span.path("operationName").asText(), refs));
            }
        }
    }

    /** FOLLOWS_FROM references from a span of one service to a span of the other one. */
    private static List<String> crossLinks(Map<String, SpanRef> spans) {
        List<String> links = new ArrayList<>();
        for (SpanRef s : spans.values()) {
            for (String[] ref : s.refs()) {
                SpanRef target = spans.get(ref[2]);
                if ("FOLLOWS_FROM".equals(ref[0]) && target != null && !target.service().equals(s.service())) {
                    links.add(s.service() + " " + s.operation() + " (trace " + s.traceId() + ") FOLLOWS_FROM "
                            + target.service() + " " + target.operation() + " (trace " + target.traceId() + ")");
                }
            }
        }
        return links;
    }

    /** The JSON log lines whose MDC carries the chain: their message and stint ids. */
    private static List<String> chainLines(App app, String chain) {
        List<String> out = new ArrayList<>();
        for (String line : app.lines()) {
            if (!line.contains(chain)) {
                continue;
            }
            try {
                JsonNode json = Spy.JSON.readTree(line);
                JsonNode mdc = json.path("mdc");
                if (chain.equals(mdc.path("stint.chainId").asText())) {
                    out.add(json.path("loggerName").asText().replaceAll(".*\\.", "") + ": " + json.path("message").asText()
                            + " {stint.chainId=" + mdc.path("stint.chainId").asText() + ", stint.instanceId="
                            + mdc.path("stint.instanceId").asText() + ", traceId=" + mdc.path("traceId").asText() + "}");
                }
            } catch (Exception e) {
                // not a JSON line (e.g. the JVM's own output)
            }
        }
        return out;
    }
}
