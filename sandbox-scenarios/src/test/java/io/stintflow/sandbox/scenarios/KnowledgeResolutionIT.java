package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;

/**
 * SDD S.13, RF1 and RF2 (CA1, CA2): texts that write the same glossary terms in different ways (case, plural, inside a
 * longer phrase) are fed to the Business base through the register's own fact; the base must hold each term once, the
 * inspector must show no candidate for duplicate among them, and a question asked from outside must answer with the ids
 * of the documents that name the term, never with a question or a content on the bus.
 */
class KnowledgeResolutionIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final int INSPECTOR = 8197;
    static final String KB = "sandbox/business-kb";
    static final Map<String, String> TEXTS = Map.of(
            "rec-rv-1", "Members may borrow books. A late return blocks new loans.",
            "rec-rv-2", "MEMBERS can BORROW BOOK copies. LATE RETURNS block LOAN requests.",
            "rec-rv-3", "The limit per member is three. A Late Return policy applies to every Member and every Book.");

    @Test
    void variations_of_a_term_are_one_node_and_a_question_from_outside_names_the_documents() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR)));
        KnowledgePlant plant = new KnowledgePlant(floci);
        for (String id : new TreeSet<>(TEXTS.keySet())) {
            plant.register(id, 1, TEXTS.get(id), "rv");
        }
        spy.until("the three texts indexed", () -> spy.events().stream().filter(e -> e.type().equals(
                "io.sandbox.business.document-indexed.v1")).count() >= 3, Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(3));

        JsonNode view = json("/graph/" + KB + "/view");
        List<String> terms = new ArrayList<>();
        for (JsonNode node : view.path("nodes")) {
            if ("Term".equals(node.path("type").asText())) {
                terms.add(node.path("label").asText().toLowerCase());
            }
        }
        note("terms in the base: %s", terms);
        // member, book, loan, late return, limit: five terms named in nine ways
        assertThat(terms).as("each glossary term once, however the text wrote it").doesNotHaveDuplicates().hasSize(5);
        JsonNode duplicates = json("/graph/" + KB + "/duplicates");
        assertThat(duplicates.size()).as("no candidates for duplicate among them").isZero();

        plant.ask("business", "q-late", "late return blocks loans");
        spy.until("the question answered", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.business.question-answered.v1")), Duration.ofMinutes(2));
        Spy.Event answered = spy.events().stream().filter(e -> e.type().equals(
                "io.sandbox.business.question-answered.v1")).findFirst().orElseThrow();
        JsonNode data = Spy.JSON.readTree(answered.json()).path("data");
        List<String> documents = new ArrayList<>();
        data.path("documents").forEach(d -> documents.add(d.asText()));
        assertThat(documents).as("the documents that name a late return").contains("rec-rv-1", "rec-rv-2", "rec-rv-3");
        assertThat(answered.json()).as("no question and no content on the bus").doesNotContain("blocks new loans")
                .doesNotContain("late return blocks loans");

        List<String> shown = List.of(view.toString(), duplicates.toString(), json("/graph/" + KB + "/metrics").toString(),
                json("/graph/" + KB + "/changes?after=0&limit=500").toString());
        Sweeper sweeper = new Sweeper(corpus(), floci, mail, Module.CHANNELS.port());
        List<Sweeper.Finding> found = new ArrayList<>(sweeper.texts("inspector", shown));
        found.addAll(sweeper.texts("bus", spy.events().stream().map(Spy.Event::json).toList()));
        for (App app : apps) {
            found.addAll(sweeper.texts("log " + app.name(), app.lines()));
        }
        found.addAll(sweeper.stores());
        assertThat(found).as("planted markers, in any form").isEmpty();
        Map<String, Leftovers.Domain> left = leftovers.awaitQuiet(spy, FLOW, Leftovers.PIPELINE);
        for (Leftovers.Domain d : left.values()) {
            assertThat(d.nonTerminal()).as(d.domain() + " non-terminal instances").isEmpty();
            assertThat(d.dlqMessages()).as(d.domain() + " DLQ").isZero();
        }
        Scenario.evidence("S.13-CA1-2.txt", "S.13 CA1 and CA2: nine ways to write five terms, one node each\n" + timeline()
                + "  terms in the base: " + terms + "\n  candidates for duplicate: " + duplicates.size() + "\n"
                + "  question answered with documents " + documents + " (mode " + data.path("mode").asText()
                + ", counts " + data.path("counts") + ")\n  markers found: " + found + "\n" + Leftovers.report(left));
    }

    private JsonNode json(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + INSPECTOR + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }
}
