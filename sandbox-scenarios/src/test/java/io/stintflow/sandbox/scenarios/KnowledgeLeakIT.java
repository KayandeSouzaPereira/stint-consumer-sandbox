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

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;

/**
 * SDD S.13, RF7 (CA7): the positive control of the sweeper over the bases. A text with a planted personal-data marker is
 * put in the register (a stand-in for a writer that let it through) and announced to the Business base. The base must not
 * take it: no node, no passage, no fact says it was indexed; what the sweeper finds is found where the plant is (the
 * register's bucket, and the batch the extraction wrote before the write was refused), and the sweeper does find it there —
 * so a leak into a base would be seen the same way. Nothing of the marker in the bus, the logs, the spans or the inspector.
 */
class KnowledgeLeakIT extends PipelineScenario {

    static final String OPERATOR = "ops";

    @Test
    void a_text_with_a_marker_does_not_reach_the_base_and_the_sweeper_sees_where_it_is() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        String written = CORPUS.meeting("p2-c1-business").pii().stream().filter(p -> p.kind().equals("email")).findFirst()
                .orElseThrow().written();
        KnowledgePlant plant = new KnowledgePlant(floci);
        plant.register("rec-lk-1", 1, "Members may borrow books. Reach the board at " + written + " for the loans.", "lk");
        plant.register("rec-lk-2", 1, "Members may borrow books. A late return blocks new loans.", "lk");
        spy.until("the clean text indexed", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.business.document-indexed.v1") && "rec-lk-2".equals(subject(e))), Duration.ofMinutes(3));
        Map<String, Leftovers.Domain> left = leftovers.awaitQuiet(spy, FLOW, Leftovers.PIPELINE);
        spy.settle(Duration.ofSeconds(8));

        assertThat(spy.events().stream().filter(e -> "rec-lk-1".equals(subject(e)) && e.type().equals(
                "io.sandbox.business.document-indexed.v1"))).as("the text with a marker is never reported as indexed").isEmpty();
        JsonNode view = json("/graph/sandbox/business-kb/view");
        JsonNode changes = json("/graph/sandbox/business-kb/changes?after=0&limit=500&document=meeting:rec-lk-1");
        assertThat(changes.size()).as("the base holds nothing of the text with a marker").isZero();
        assertThat(view.path("nodes").size()).as("the clean text is there").isPositive();
        assertThat(get8198("/graph/sandbox/engineering-kb/changes?after=0&limit=500&document=meeting:rec-lk-1"))
                .as("the Engineering base holds nothing of it either").isEqualTo("[]");

        Sweeper sweeper = new Sweeper(corpus(), floci, mail, Module.CHANNELS.port());
        List<Sweeper.Finding> inBus = new ArrayList<>(sweeper.texts("bus", spy.events().stream().map(Spy.Event::json).toList()));
        for (App app : apps) {
            inBus.addAll(sweeper.texts("log " + app.name(), app.lines()));
        }
        inBus.addAll(sweeper.texts("inspector", List.of(view.toString(), json("/graph/sandbox/business-kb/metrics").toString(),
                json("/graph/sandbox/business-kb/changes?after=0&limit=500").toString(),
                get("/graph/sandbox/business-kb/export.graphml"))));
        assertThat(inBus).as("the marker in the bus, the logs and the inspector").isEmpty();

        List<Sweeper.Finding> stores = sweeper.stores();
        assertThat(stores).as("the sweeper does see the marker where it was planted").isNotEmpty();
        assertThat(stores).as("and only in the register's bucket or in the batch blob of the refused text")
                .allSatisfy(f -> assertThat(f.where()).matches(w -> w.contains("sandbox-records-docs")
                        || w.contains("sandbox-business-blobs/retention/knowledge-batch")
                        || w.contains("sandbox-engineering-blobs/retention/knowledge-batch")));
        Scenario.evidence("S.13-CA7.txt", "S.13 CA7: a text with a planted marker is refused by the base; the sweeper finds"
                + " it where it was planted\n" + timeline() + "  not indexed; base changes for the document: " + changes.size()
                + "\n  found in stores: " + stores + "\n  found in bus, logs, inspector: " + inBus + "\n" + Leftovers.report(left));
    }

    private String get8198(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8198" + path)).header("X-Stint-Operator",
                OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8197" + path)).header("X-Stint-Operator",
                OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private JsonNode json(String path) throws Exception {
        return Spy.JSON.readTree(get(path));
    }
}
