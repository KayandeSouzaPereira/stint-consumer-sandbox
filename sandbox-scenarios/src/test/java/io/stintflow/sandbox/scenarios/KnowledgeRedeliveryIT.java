package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.13, RF3 (CA3): the fact that registers a document reaches the base more than once. The same fact again (the same
 * id: a redelivery) and the same document announced under another id (a second announcement) leave the base as it was —
 * the same nodes, the same change log — and the second announcement is answered {@code UNCHANGED}. A worker dying around
 * the write is the chaos scenario of the S.12; the base lives in the worker's memory (finding S12-1), so it cannot be
 * proved against the same base here.
 */
class KnowledgeRedeliveryIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String KB = "sandbox/business-kb";
    static final String TEXT = "Members may borrow books. A late return blocks new loans.";

    @Test
    void a_fact_delivered_again_leaves_the_base_as_it_was() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR)));
        KnowledgePlant plant = new KnowledgePlant(floci);
        plant.register("rec-rd-1", 1, TEXT, "rd");
        spy.until("indexed", () -> indexed() >= 1, Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(3));
        JsonNode nodes = json("/graph/" + KB + "/view");
        int changes = json("/graph/" + KB + "/changes?after=0&limit=500").size();
        assertThat(changes).isPositive();

        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TEXT.getBytes(StandardCharsets.UTF_8)));
        plant.announce("rec-rd-1", 1, sha, "rd");                       // the same fact, the same id
        plant.announce("rec-rd-1", 1, sha, "rd", "rd:plant:second");   // the same document, announced again
        spy.until("the second announcement answered", () -> indexed() >= 2, Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(8));

        assertThat(json("/graph/" + KB + "/view").path("nodes").size()).isEqualTo(nodes.path("nodes").size());
        assertThat(json("/graph/" + KB + "/view").path("edges").size()).isEqualTo(nodes.path("edges").size());
        assertThat(json("/graph/" + KB + "/changes?after=0&limit=500").size()).as("no new change in the log")
                .isEqualTo(changes);
        List<String> statuses = spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.document-indexed.v1"))
                .map(e -> status(e)).toList();
        assertThat(statuses.getFirst()).isEqualTo("APPLIED");
        assertThat(statuses.subList(1, statuses.size())).as("every later answer").allMatch("UNCHANGED"::equals);
        Scenario.evidence("S.13-CA3.txt", "S.13 CA3: the fact delivered again\n" + timeline() + "  nodes "
                + nodes.path("nodes").size() + ", changes " + changes + " before and after; answers " + statuses + "\n");
    }

    private long indexed() {
        return spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.document-indexed.v1")).count();
    }

    private static String status(Spy.Event e) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path("status").asText();
        } catch (Exception ex) {
            return "";
        }
    }

    private JsonNode json(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8197" + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }
}
