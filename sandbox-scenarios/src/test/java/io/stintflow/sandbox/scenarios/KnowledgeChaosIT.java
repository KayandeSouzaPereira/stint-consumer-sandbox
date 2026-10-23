package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * SDD S.12, RF10 (CA9): the Business worker dies inside a task of its knowledge base. The base lives in the worker's memory
 * (Stint 3b has no other adapter, finding S12-1), so what dies with it is what it held; what must hold is what the engine
 * and the blobs keep: the task comes back to the next worker, the write happens once, the fact is announced once, nothing is
 * left halfway, and nothing is leaked.
 */
class KnowledgeChaosIT extends PipelineScenario {

    static final Duration EXIT = Duration.ofSeconds(30);
    static final String OPERATOR = "ops";
    static final int INSPECTOR = 8197;
    static final Map<String, String> ENGINE_ONLY = Map.of("business.worker.enabled", "false");
    static final Map<String, String> WORKER_ONLY = Map.of("business.engine.enabled", "false", "quarkus.http.port", "8099",
            "business.knowledge.operators", OPERATOR);

    @Test
    void a_worker_killed_inside_the_write_leaves_one_document_indexed_once() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, ENGINE_ONLY));
        App worker1 = start("business-worker-1", Module.BUSINESS, with(WORKER_ONLY, "business.chaos.halt-in-task",
                "knowledge-write"));
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        awaitAnswering("the business worker halted in knowledge-write", () -> !worker1.alive(), CYCLE);
        String halt = worker1.awaitLine(l -> l.contains(HALT), Duration.ofSeconds(5));
        int exit = worker1.awaitExit(EXIT);
        note("business worker 1 halted, exit %d: %s", exit, message(halt));
        assertThat(exit).isEqualTo(137);
        assertThat(indexed()).as("nothing indexed by a worker that died before it wrote").isEmpty();

        start("business-worker-2", Module.BUSINESS, WORKER_ONLY);
        awaitAnswering("the design was indexed after the task came back", () -> !indexed().isEmpty(), CYCLE);
        awaitClosed("p1", "1");
        spy.settle(Duration.ofSeconds(8));
        List<Spy.Event> indexed = indexed();
        assertThat(indexed.stream().map(e -> subject(e) + " v" + data(e, "version")).sorted().toList())
                .as("each registered design indexed once").doesNotHaveDuplicates().contains("rec-p1-c1-design v1");
        JsonNode changes = Spy.JSON.readTree(inspect("/graph/sandbox/business-kb/changes?after=0&limit=500").body());
        JsonNode view = Spy.JSON.readTree(inspect("/graph/sandbox/business-kb/view").body());
        assertThat(view.path("nodes").size()).as("the second worker's base holds the design").isPositive();
        assertThat(changes.size()).isPositive();

        assertTheCountsOfACompleteCycle(eventsOf("p1"), "p1");
        String closing = verify(List.of(PipelineEvents.chainIdOf("p1")), true);
        Scenario.evidence("S.12-CA9-write.txt", "S.12 RF10: the business worker killed inside knowledge-write\n" + timeline()
                + "  result: the task came back to the second worker; the design is indexed once ("
                + indexed.size() + " fact(s)); its base has " + view.path("nodes").size() + " nodes and "
                + changes.size() + " changes\n" + busOf("p1", eventsOf("p1")) + closing);
    }

    @Test
    void b_worker_killed_inside_an_erasure_finishes_it_when_the_task_comes_back() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, ENGINE_ONLY));
        App worker1 = start("business-worker-1", Module.BUSINESS, with(WORKER_ONLY, "business.chaos.halt-in-task",
                "knowledge-erase"));
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        awaitClosed("p1", "1");
        spy.until("the design indexed", () -> !indexed().isEmpty(), Duration.ofMinutes(2));
        request("er-1");
        awaitAnswering("the business worker halted in knowledge-erase", () -> !worker1.alive(), CYCLE);
        int exit = worker1.awaitExit(EXIT);
        assertThat(exit).isEqualTo(137);
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.erasure-completed.v1")))
                .as("not announced by a worker that died before it erased").isEmpty();

        start("business-worker-2", Module.BUSINESS, WORKER_ONLY);
        spy.until("the erasure completed", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.stintflow.knowledge.erasure-completed.v1") && "er-1".equals(subject(e))), Duration.ofMinutes(3));
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.erasure-incomplete.v1")))
                .isEmpty();
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.erasure-completed.v1")
                && "er-1".equals(subject(e)))).as("completed once").hasSize(1);
        spy.settle(Duration.ofSeconds(8));
        String closing = verify(List.of(PipelineEvents.chainIdOf("p1")), true);
        Scenario.evidence("S.12-CA9-erase.txt", "S.12 RF10: the business worker killed inside knowledge-erase\n" + timeline()
                + "  result: the task came back to the second worker; the erasure was verified and announced once\n"
                + closing);
    }

    private void request(String requestId) throws Exception {
        String detail = Spy.JSON.writeValueAsString(Spy.JSON.createObjectNode().put("specversion", "1.0")
                .put("id", requestId + "-fact").put("type", "io.stintflow.knowledge.erasure-requested.v1")
                .put("source", "https://sandbox.stintflow.io/data-protection").put("datacontenttype", "application/json")
                .set("data", Spy.JSON.createObjectNode().put("requestId", requestId).put("kind", "document")
                        .put("namespace", "sandbox").put("base", "business-kb").put("documentId", "meeting:rec-p1-c1-design")
                        .put("label", "internal")));
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/data-protection").detailType(
                        "io.stintflow.knowledge.erasure-requested.v1").detail(detail).build()));
        note("requested the erasure %s", requestId);
    }

    private List<Spy.Event> indexed() {
        return spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.document-indexed.v1")).toList();
    }

    private HttpResponse<String> inspect(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + INSPECTOR + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String data(Spy.Event e, String field) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path(field).asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
