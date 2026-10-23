package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;

/**
 * SDD S.12, CA1 to CA5 and CA7: project p2 (two cycles, a design and an SDD rewritten once) with the knowledge base of
 * Business and of Engineering learning from {@code document-registered}. Watched from outside: the bus (every registered
 * document of a base's kinds is announced as indexed, once, on the project's chain) and each domain's read-only inspector
 * (closed to anybody who is not an operator, the base grows, nothing restricted in what it shows). Then the sweeper over
 * everything, including what the inspector answered.
 */
class KnowledgeIngestIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final int BUSINESS_INSPECTOR = 8097;
    static final int ENGINEERING_INSPECTOR = 8098;
    static final String BUSINESS_KB = "sandbox/business-kb";
    static final String ENGINEERING_KB = "sandbox/engineering-kb";

    @Test
    void both_bases_learn_from_the_registered_documents_and_the_inspector_shows_them_grow() throws Exception {
        startPipeline(Map.of(
                Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        portal.rejectItemOnce("p2-c2-batch", "p2-sdd-1");
        portal.rejectFirstApprovalOf("p2-c1-design");

        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        note("recorded p2-c1-business");
        awaitClosed("p2", "2");
        spy.settle(Duration.ofSeconds(5));

        // --- the bus: each registered document of a base's kinds is indexed once, by that base, on the project's chain ---
        String chain = PipelineEvents.chainIdOf("p2");
        List<Spy.Event> registered = spy.events().stream().filter(e -> e.type().equals(PipelineEvents.DOCUMENT_REGISTERED))
                .filter(e -> project(e, "p2")).toList();
        Map<String, List<String>> kinds = Map.of("business", List.of("design"),
                "engineering", List.of("design", "questions", "sdd-batch", "sdd"));
        StringBuilder bus = new StringBuilder("  documents registered on p2: " + registered.size() + "\n");
        for (Map.Entry<String, List<String>> base : kinds.entrySet()) {
            String type = "io.sandbox." + base.getKey() + ".document-indexed.v1";
            List<String> expected = registered.stream().filter(e -> base.getValue().contains(data(e, "kind")))
                    .map(e -> data(e, "recordId") + " v" + data(e, "version")).sorted().toList();
            List<Spy.Event> indexed = spy.events().stream().filter(e -> e.type().equals(type)).toList();
            List<String> got = indexed.stream().map(e -> data(e, "recordId") + " v" + data(e, "version")).sorted().toList();
            assertThat(got).as(base.getKey() + " indexed exactly what it may read").isEqualTo(expected);
            assertThat(indexed).allMatch(e -> chain.equals(e.chainId()), "on the chain of p2");
            assertThat(spy.events().stream().filter(e -> e.type().equals("io.sandbox." + base.getKey()
                    + ".ingest-rejected.v1"))).as(base.getKey() + " rejected nothing").isEmpty();
            bus.append("  ").append(base.getKey()).append(" indexed: ").append(got).append('\n');
        }
        // the rewritten design is a second version of the same record
        assertThat(registered.stream().filter(e -> data(e, "recordId").equals("rec-p2-c1-design"))).hasSize(2);

        // --- recovery with origin (RF5, CA4): the second cycle's design cites the first cycle's, which the first one cannot ---
        String second = stored("rec-p2-c2-design", 1);
        String first = stored("rec-p2-c1-design", 1);
        assertThat(second).as("the second design ends with the documents the base found").contains(
                "## Context from earlier documents").contains("rec-p2-c1-design");
        assertThat(second).as("a design does not cite itself").doesNotContain("- rec-p2-c2-design");
        assertThat(first).as("the first design, over an empty base, cites nothing").doesNotContain("## Context");
        bus.append("  p2's second design cites: ").append(second.lines().skip(second.lines().toList().indexOf(
                "## Context from earlier documents") + 1).toList()).append('\n');

        // --- the inspectors: closed to everybody else, open to the operator, the base grows, nothing restricted ---
        List<String> shown = new ArrayList<>();
        StringBuilder look = new StringBuilder();
        String[][] bases = {{"business", String.valueOf(BUSINESS_INSPECTOR), BUSINESS_KB},
            {"engineering", String.valueOf(ENGINEERING_INSPECTOR), ENGINEERING_KB}};
        for (String[] base : bases) {
            int port = Integer.parseInt(base[1]);
            String kb = base[2];
            assertThat(inspect(port, "/graph/bases", null).body()).as("nobody is on no list").isEqualTo("[]");
            assertThat(inspect(port, "/graph/" + kb + "/view", null).statusCode()).isEqualTo(403);
            assertThat(inspect(port, "/graph/" + kb + "/view", "somebody").statusCode()).isEqualTo(403);
            assertThat(Spy.JSON.readTree(inspect(port, "/graph/bases", OPERATOR).body()).size()).isEqualTo(1);
            HttpResponse<String> view = inspect(port, "/graph/" + kb + "/view", OPERATOR);
            assertThat(view.statusCode()).isEqualTo(200);
            JsonNode current = Spy.JSON.readTree(view.body());
            assertThat(current.path("nodes").size()).as(base[0] + " graph has nodes").isPositive();
            JsonNode changes = Spy.JSON.readTree(inspect(port, "/graph/" + kb + "/changes?after=0&limit=500", OPERATOR)
                    .body());
            assertThat(changes.size()).as(base[0] + " change log").isPositive();
            JsonNode ofChain = Spy.JSON.readTree(inspect(port, "/graph/" + kb + "/changes?after=0&limit=500&chain="
                    + chain, OPERATOR).body());
            assertThat(ofChain.size()).as("the chain of p2 made every change of " + base[0]).isEqualTo(changes.size());
            JsonNode metrics = Spy.JSON.readTree(inspect(port, "/graph/" + kb + "/metrics", OPERATOR).body());
            shown.add(view.body());
            shown.add(changes.toString());
            shown.add(metrics.toString());
            shown.add(inspect(port, "/graph/" + kb + "/export.graphml", OPERATOR).body());
            look.append(String.format("  %-12s nodes %d, edges %d, changes %d%n", base[0], current.path("nodes").size(),
                    current.path("edges").size(), changes.size()));
        }

        Sweeper sweeper = new Sweeper(corpus(), floci, mail, Module.CHANNELS.port());
        assertThat(sweeper.texts("inspector", shown)).as("planted markers in what the inspectors show").isEmpty();
        String closing = verify(List.of(chain), true);
        Scenario.evidence("S.12-CA1-5.txt", "S.12 CA1 to CA5 and CA7: p2's two cycles with both bases learning\n"
                + timeline() + bus + look + closing);
    }

    /** The text of a version of a registered document, read from the register's copy. */
    private String stored(String recordId, int version) {
        var item = floci.ddb.getItem(r -> r.tableName("records-catalog").consistentRead(true).key(Map.of(
                "recordId", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(recordId).build(),
                "version", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().n(
                        String.valueOf(version)).build()))).item();
        URI ref = URI.create(item.get("storedRef").s());
        return floci.s3.getObjectAsBytes(r -> r.bucket(ref.getHost()).key(ref.getPath().substring(1)))
                .asUtf8String();
    }

    private HttpResponse<String> inspect(int port, String path, String operator) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (operator != null) {
            request.header("X-Stint-Operator", operator);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String data(Spy.Event e, String field) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path(field).asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
