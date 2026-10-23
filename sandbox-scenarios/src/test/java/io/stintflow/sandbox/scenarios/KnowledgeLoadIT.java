package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.13, RF6 (CA6): M projects at the same time (the corpus of the S.11 load scenario) with the two bases learning from
 * every document the pipeline registers. For each base: each registered document of its kinds is indexed once and nothing
 * else; the time from the fact to the answer (observation only, p50 and p95); no candidate for duplicate; and the closing
 * check of the pipeline (contract, sweeper, nothing left over).
 */
class KnowledgeLoadIT extends PipelineScenario {

    static final int PROJECTS = Integer.getInteger("scenarios.projects", 6);
    static final Duration WITHIN = Duration.ofMinutes(25);
    static final String OPERATOR = "ops";

    private Corpus generated;

    @Override
    protected Corpus corpus() {
        return generated;
    }

    @Test
    void m_projects_feed_both_bases_without_a_duplicate_and_with_nothing_left_over() throws Exception {
        Path dir = Scenario.out().resolve("load-corpus");
        List<String> projects = PipelineLoadIT.generate(dir, PROJECTS);
        generated = Corpus.load(dir);
        Map<Module, Map<String, String>> props = new LinkedHashMap<>();
        for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.TARGET, Module.BUSINESS,
                Module.ENGINEERING, Module.DELIVERY)) {
            props.put(m, new LinkedHashMap<>(Map.of("sandbox.corpus-dir", dir.toString())));
        }
        props.get(Module.BUSINESS).put("business.knowledge.operators", OPERATOR);
        props.get(Module.ENGINEERING).put("engineering.knowledge.operators", OPERATOR);
        startPipeline(props);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(projects.size());
        List<Future<Integer>> recorded = new ArrayList<>();
        for (String project : projects) {
            recorded.add(pool.submit(() -> {
                go.await();
                return portal.record(project + "-c1-business");
            }));
        }
        go.countDown();
        for (Future<Integer> status : recorded) {
            assertThat(status.get()).isEqualTo(202);
        }
        pool.shutdown();
        for (String project : projects) {
            awaitClosed(project, "1", WITHIN);
        }
        spy.settle(Duration.ofSeconds(10));

        List<String> chains = new ArrayList<>();
        for (String project : projects) {
            assertTheCountsOfACompleteCycle(eventsOf(project), project);
            chains.add(PipelineEvents.chainIdOf(project));
        }
        StringBuilder look = new StringBuilder();
        Map<String, List<String>> kinds = Map.of("business", List.of("design"),
                "engineering", List.of("design", "questions", "sdd-batch", "sdd"));
        Map<String, Integer> inspectors = Map.of("business", 8197, "engineering", 8198);
        List<String> shown = new ArrayList<>();
        for (Map.Entry<String, List<String>> base : kinds.entrySet()) {
            List<Spy.Event> registered = spy.events().stream().filter(e -> e.type().equals(PipelineEvents.DOCUMENT_REGISTERED))
                    .filter(e -> base.getValue().contains(data(e, "kind"))).toList();
            List<Spy.Event> indexed = spy.events().stream().filter(e -> e.type().equals("io.sandbox." + base.getKey()
                    + ".document-indexed.v1")).toList();
            List<String> expected = registered.stream().map(e -> data(e, "recordId") + " v" + data(e, "version")).sorted().toList();
            List<String> got = indexed.stream().map(e -> data(e, "recordId") + " v" + data(e, "version")).sorted().toList();
            assertThat(got).as(base.getKey() + " indexed what it may read, once").isEqualTo(expected);
            List<Double> seconds = new ArrayList<>();
            for (Spy.Event in : indexed) {
                Instant at = registered.stream().filter(r -> data(r, "recordId").equals(data(in, "recordId"))
                        && data(r, "version").equals(data(in, "version"))).map(Spy.Event::seenAt).min(Instant::compareTo)
                        .orElseThrow();
                seconds.add(Duration.between(at, in.seenAt()).toMillis() / 1000.0);
            }
            JsonNode view = json(inspectors.get(base.getKey()), "/graph/sandbox/" + base.getKey() + "-kb/view");
            JsonNode duplicates = json(inspectors.get(base.getKey()), "/graph/sandbox/" + base.getKey() + "-kb/duplicates");
            look.append("  ").append(base.getKey()).append(" candidates for duplicate: ").append(duplicates).append('\n');
            // a document is a Meeting node named after its record, so two documents of one project (a batch and its
            // design) share words and the heuristic flags them (finding S13-2); a glossary term must never be flagged
            List<String> flagged = new ArrayList<>();
            duplicates.forEach(c -> flagged.add(c.path("type").asText()));
            assertThat(flagged).as(base.getKey() + " candidates for duplicate that are not documents").allMatch("Meeting"::equals);
            shown.add(view.toString());
            shown.add(duplicates.toString());
            look.append(String.format("  %-12s %d documents indexed once; fact to answer p50 %.1f s, p95 %.1f s; %d nodes, %d edges%n",
                    base.getKey(), got.size(), PipelineLoadIT.percentile(seconds, 50), PipelineLoadIT.percentile(seconds, 95),
                    view.path("nodes").size(), view.path("edges").size()));
        }
        assertThat(new io.stintflow.sandbox.scenarios.support.Sweeper(corpus(), floci, mail, Module.CHANNELS.port())
                .texts("inspector", shown)).isEmpty();
        String closing = verify(chains, true);
        Scenario.evidence("S.13-CA6.txt", "S.13 CA6: " + projects.size() + " simultaneous projects, both bases learning\n"
                + timeline() + look + closing);
    }

    private JsonNode json(int port, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }

    private static String data(Spy.Event e, String field) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path(field).asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
