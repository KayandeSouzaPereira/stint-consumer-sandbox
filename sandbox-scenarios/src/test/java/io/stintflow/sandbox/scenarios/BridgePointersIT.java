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

import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;

/**
 * SDD S.14, CA1: project p2's second business meeting holds decisions and a requirement (the answer key in
 * {@code corpus/kb.json}). Business announces the nodes its base now holds as pointers (id, type, level); Engineering holds a
 * {@code DomainReference} per pointer in its architecture base and links its anchor to it. Seen from outside: the
 * announcement and the architecture base carry no label, no summary and no property of what Business decided.
 */
class BridgePointersIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final int ARCHITECTURE_INSPECTOR = 8199;

    @Test
    void engineering_holds_pointers_to_what_business_decided_and_nothing_of_it() throws Exception {
        startPipeline(Map.of(
                Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        portal.rejectItemOnce("p2-c2-batch", "p2-sdd-1");
        portal.rejectFirstApprovalOf("p2-c1-design");
        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        awaitClosed("p2", "2");
        spy.until("the references linked", () -> !events("io.sandbox.engineering.references-linked.v1").isEmpty(),
                Duration.ofMinutes(2));
        spy.settle(Duration.ofSeconds(8));

        KnowledgeCorpus kb = KnowledgeCorpus.load(Scenario.root().resolve("corpus"));
        List<String> secrets = new ArrayList<>();
        kb.decisions().forEach(d -> {
            secrets.add(d.label());
            secrets.add(d.summary());
        });
        kb.requirements().forEach(r -> {
            secrets.add(r.label());
            secrets.add(r.summary());
        });

        // --- the announcement: pointers, types, levels ---
        List<Spy.Event> published = events("io.sandbox.business.nodes-published.v1");
        assertThat(published).as("Business announced what its design added").isNotEmpty();
        int pointers = 0;
        for (Spy.Event event : published) {
            JsonNode data = Spy.JSON.readTree(event.json()).path("data");
            assertThat(data.path("domain").path("base").asText()).isEqualTo("business-kb");
            for (JsonNode item : data.path("items")) {
                pointers++;
                assertThat(item.path("pointer").asText()).startsWith("stint-kg://sandbox/business-kb/");
                assertThat(item.path("type").asText()).isIn("Decision", "Requirement");
                assertThat(item.path("level").asText()).isIn("internal", "confidential");
                assertThat(item.fieldNames()).toIterable().containsOnly("pointer", "type", "level");
            }
            for (String secret : secrets) {
                assertThat(event.json()).as("nothing of what Business decided in the announcement").doesNotContain(secret);
            }
        }
        assertThat(pointers).as("pointers announced").isPositive();

        // --- the references: one DomainReference per pointer, no name of the node, no content ---
        List<Spy.Event> linked = events("io.sandbox.engineering.references-linked.v1");
        assertThat(linked).isNotEmpty();
        JsonNode view = Spy.JSON.readTree(arch("/graph/sandbox/engineering-arch/view"));
        long references = 0;
        for (JsonNode node : view.path("nodes")) {
            if ("DomainReference".equals(node.path("type").asText())) {
                references++;
            }
            assertThat(node.path("type").asText()).as("Engineering made no Decision of Business's").isNotIn("Decision",
                    "Requirement");
        }
        assertThat(references).as("a reference for each pointer announced, once").isEqualTo(pointers);
        String shown = view.toString() + arch("/graph/sandbox/engineering-arch/changes?after=0&limit=500")
                + arch("/graph/sandbox/engineering-arch/export.graphml");
        for (String secret : secrets) {
            assertThat(shown).as("nothing of what Business decided in the architecture base").doesNotContain(secret);
        }
        for (Spy.Event event : linked) {
            for (String secret : secrets) {
                assertThat(event.json()).doesNotContain(secret);
            }
        }

        Sweeper sweeper = new Sweeper(corpus(), floci, mail, Module.CHANNELS.port());
        assertThat(sweeper.texts("architecture", List.of(shown))).isEmpty();
        String closing = verify(List.of(PipelineEvents.chainIdOf("p2")), true);
        Scenario.evidence("S.14-CA1.txt", "S.14 CA1: pointers announced by Business and held by Engineering\n" + timeline()
                + "  pointers announced: " + pointers + "; DomainReference nodes in the architecture base: " + references
                + "\n  the labels and summaries of the answer key found in the announcement, the base and the events: none\n"
                + closing);
    }

    private List<Spy.Event> events(String type) {
        return spy.events().stream().filter(e -> e.type().equals(type)).toList();
    }

    private String arch(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + ARCHITECTURE_INSPECTOR + path)).header("X-Stint-Operator", OPERATOR).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }
}
