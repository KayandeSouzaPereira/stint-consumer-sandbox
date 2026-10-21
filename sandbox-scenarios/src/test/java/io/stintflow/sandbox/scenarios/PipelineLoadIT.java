package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.11, RF4 (CA4): M projects at the same time ({@code -Dscenarios.projects}, default 6), each a full cycle of the
 * corpus's p1 (a business meeting, two SDDs, one failing its first attempt). The corpus has two complete projects, so
 * the scenario generates its own corpus in the output directory — p1's two meetings cloned under the ids l1..lM, with
 * the project id, the meeting ids and the SDD ids renamed and nothing else changed — and points every process at it
 * ({@code sandbox.corpus-dir}). The committed corpus is not touched. The recordings are released together by a latch.
 * Then, for each project: the counts of a complete cycle, its own chain, and the closing check of
 * {@link PipelineScenario#verify}. The time of each stage, per project, is recorded only as an observation (p50, p95).
 */
class PipelineLoadIT extends PipelineScenario {

    static final int PROJECTS = Integer.getInteger("scenarios.projects", 6);
    static final Duration WITHIN = Duration.ofMinutes(25);

    private Corpus generated;

    @Override
    protected Corpus corpus() {
        return generated;
    }

    @Test
    void m_simultaneous_projects_all_close_their_cycle_with_nothing_left_over() throws Exception {
        Path dir = Scenario.out().resolve("load-corpus");
        List<String> projects = generate(dir, PROJECTS);
        generated = Corpus.load(dir);
        Map<String, String> corpusDir = Map.of("sandbox.corpus-dir", dir.toString());
        Map<Module, Map<String, String>> props = new LinkedHashMap<>();
        for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.TARGET, Module.BUSINESS,
                Module.ENGINEERING, Module.DELIVERY)) {
            props.put(m, corpusDir);
        }
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
        Instant released = Instant.now();
        go.countDown();
        for (Future<Integer> status : recorded) {
            assertThat(status.get()).isEqualTo(202);
        }
        pool.shutdown();
        note("%d projects released together: %s", projects.size(), projects);

        for (String project : projects) {
            awaitClosed(project, "1", WITHIN);
        }
        spy.settle(Duration.ofSeconds(5));

        List<String> chains = new ArrayList<>();
        StringBuilder bus = new StringBuilder();
        Map<String, List<Double>> stages = new LinkedHashMap<>();
        for (String project : projects) {
            List<Spy.Event> events = eventsOf(project);
            assertTheCountsOfACompleteCycle(events, project);
            chains.add(PipelineEvents.chainIdOf(project));
            bus.append(busOf(project, events));
            Instant first = events.stream().filter(e -> e.type().equals(PipelineEvents.MEETING_RECORDED))
                    .map(Spy.Event::time).min(Instant::compareTo).orElseThrow();
            stage(stages, "recorded -> design published", first, last(events, PipelineEvents.DESIGN_PUBLISHED, false));
            stage(stages, "recorded -> batch approved", first, last(events, PipelineEvents.SDD_BATCH_APPROVED, false));
            stage(stages, "recorded -> last SDD validated", first, last(events, PipelineEvents.SDD_VALIDATED, true));
            stage(stages, "recorded -> cycle closed", first, last(events, PipelineEvents.CYCLE_CLOSED, true));
        }
        assertThat(chains).as("one chain per project").doesNotHaveDuplicates().hasSize(projects.size());

        String closing = verify(chains, true);
        StringBuilder obs = new StringBuilder("  stage times across the " + projects.size()
                + " projects (observation only, seconds from the project's first recording):\n");
        stages.forEach((name, seconds) -> obs.append(String.format("    %-34s p50 %6.1f   p95 %6.1f   max %6.1f%n", name,
                percentile(seconds, 50), percentile(seconds, 95), percentile(seconds, 100))));
        obs.append(String.format("  whole run: %.1f s from the release%n",
                Duration.between(released, Instant.now()).toMillis() / 1000.0));
        Scenario.evidence("S.11-CA4.txt", "S.11 RF4: " + projects.size() + " simultaneous projects, each a full cycle of two"
                + " SDDs\n" + timeline() + "  reviews answered through the portal: " + answered.size() + "\n"
                + "  result: " + projects.size() + " chains, " + projects.size() + " cycles closed, the counts of an"
                + " undisturbed cycle in each, nothing left over\n" + obs + bus + closing);
    }

    private static Instant last(List<Spy.Event> events, String type, boolean latest) {
        var times = events.stream().filter(e -> e.type().equals(type)).map(Spy.Event::time).toList();
        return latest ? times.stream().max(Instant::compareTo).orElseThrow()
                : times.stream().min(Instant::compareTo).orElseThrow();
    }

    private static void stage(Map<String, List<Double>> stages, String name, Instant from, Instant to) {
        stages.computeIfAbsent(name, k -> new ArrayList<>()).add(Duration.between(from, to).toMillis() / 1000.0);
    }

    static double percentile(List<Double> values, int p) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** p1's business and technical meetings cloned as l1..lN: the project id, the meeting ids and the SDD ids renamed. */
    static List<String> generate(Path dir, int n) throws IOException {
        Path source = Scenario.root().resolve("corpus");
        Files.createDirectories(dir.resolve("meetings"));
        Files.createDirectories(dir.resolve("transcripts"));
        Files.createDirectories(dir.resolve("audio"));
        Files.copy(source.resolve("pii-forms.json"), dir.resolve("pii-forms.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JsonNode manifest = Spy.JSON.readTree(source.resolve("manifest.json").toFile());
        ArrayNode meetings = Spy.JSON.createArrayNode();
        List<String> projects = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String project = "l" + i;
            projects.add(project);
            for (String stage : List.of("business", "technical")) {
                String from = "p1-c1-" + stage;
                String to = project + "-c1-" + stage;
                String yaml = Files.readString(source.resolve("meetings/" + from + ".yaml"), StandardCharsets.UTF_8)
                        .replaceAll("\\bp1\\b", project);
                Files.writeString(dir.resolve("meetings/" + to + ".yaml"), yaml, StandardCharsets.UTF_8);
                Files.copy(source.resolve("audio/" + from + ".wav"), dir.resolve("audio/" + to + ".wav"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path transcript = source.resolve("transcripts/" + from + ".json");
                if (Files.exists(transcript)) {
                    Files.writeString(dir.resolve("transcripts/" + to + ".json"),
                            Files.readString(transcript, StandardCharsets.UTF_8).replaceAll("\\bp1\\b", project),
                            StandardCharsets.UTF_8);
                }
                for (JsonNode entry : manifest.path("meetings")) {
                    if (from.equals(entry.path("id").asText())) {
                        ObjectNode copy = entry.deepCopy();
                        copy.put("id", to);
                        copy.put("script", "meetings/" + to + ".yaml");
                        copy.put("scriptSha256", Corpus.sha256(yaml.getBytes(StandardCharsets.UTF_8)));
                        ((ObjectNode) copy.path("audio")).put("file", "audio/" + to + ".wav");
                        meetings.add(copy);
                    }
                }
            }
        }
        ObjectNode out = ((ObjectNode) manifest).deepCopy();
        out.set("meetings", meetings);
        Files.writeString(dir.resolve("manifest.json"), Spy.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out),
                StandardCharsets.UTF_8);
        return projects;
    }
}
