package io.stintflow.sandbox.scenarios.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;

/**
 * SDD S.11: what the pipeline's chaos and load scenarios share (the skeleton of S.5 keeps its own copies, untouched):
 * the seven domains as processes, the scripted reviewer answering through the portal between long polls, the counts
 * of a project that goes all the way, and the closing check — every event keeps the v1 contract, the PII sweeper finds
 * nothing in the bus, the process logs, the stores and the spans, and nothing is left over.
 */
public abstract class PipelineScenario extends ScenarioBase {

    protected static final Duration CYCLE = Duration.ofMinutes(8);
    protected static final Corpus CORPUS = Corpus.load(Scenario.root().resolve("corpus"));
    protected static final PipelineContracts CONTRACTS = new PipelineContracts();
    protected static final String[] ROLES = {PipelineEvents.ROLE_BUSINESS, PipelineEvents.ROLE_TECH,
        PipelineEvents.ROLE_TECHNICIAN};
    protected static final String HALT = "CHAOS halt at";

    protected final HttpClient http = HttpClient.newHttpClient();
    protected final List<String> answered = new ArrayList<>();
    protected Portal portal;

    protected static Map<String, String> with(Map<String, String> base, String... keyValues) {
        Map<String, String> out = new LinkedHashMap<>(base);
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    /** The seven domains, one process each; {@code props} overrides the properties of a module. */
    protected Map<Module, App> startPipeline(Map<Module, Map<String, String>> props) {
        Map<Module, App> running = new LinkedHashMap<>();
        for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.TARGET, Module.BUSINESS,
                Module.ENGINEERING, Module.DELIVERY)) {
            running.put(m, start(m.name().toLowerCase(), m, props.getOrDefault(m, Map.of())));
        }
        portal = new Portal(Module.CHANNELS.port());
        return running;
    }

    /** The scripted reviewer's round: every open review answered once, as the corpus key says. */
    protected void answerReviews() {
        portal.answerAsTheKey(corpus(), ROLES).forEach(a -> {
            answered.add(a);
            note("answered %s", a);
        });
    }

    /** Polls the bus until {@code done}, answering the reviews that open on the way. */
    protected void awaitAnswering(String what, BooleanSupplier done, Duration within) {
        spy.until(what, () -> {
            answerReviews();
            return done.getAsBoolean();
        }, within);
    }

    /** The corpus the scenario's processes read (a scenario that generates its own overrides this). */
    protected Corpus corpus() {
        return CORPUS;
    }

    protected void awaitClosed(String project, String cycle) {
        awaitClosed(project, cycle, CYCLE);
    }

    protected void awaitClosed(String project, String cycle, Duration within) {
        awaitAnswering(project + " cycle " + cycle + " closed", () -> spy.events().stream()
                .anyMatch(e -> e.type().equals(PipelineEvents.CYCLE_CLOSED) && project(e, project)
                        && cycle.equals(ext(e, PipelineEvents.EXT_CYCLE))), within);
        note("%s cycle %s closed", project, cycle);
    }

    protected List<Spy.Event> eventsOf(String project) {
        return spy.events().stream().filter(e -> project(e, project)).toList();
    }

    /** The counts of a project that goes through one cycle with two SDDs, one failing its first attempt (S.5 to S.10). */
    protected static void assertTheCountsOfACompleteCycle(List<Spy.Event> events, String project) {
        String chain = PipelineEvents.chainIdOf(project);
        assertThat(events).isNotEmpty().allMatch(e -> chain.equals(e.chainId()), "on the chain of " + project);
        assertThat(count(events, PipelineEvents.MEETING_RECORDED)).isEqualTo(2);
        assertThat(count(events, PipelineEvents.DESIGN_PUBLISHED)).isEqualTo(1);
        assertThat(count(events, PipelineEvents.REFINEMENT_REQUESTED)).isEqualTo(1);
        assertThat(count(events, PipelineEvents.SDD_BATCH_APPROVED)).isEqualTo(1);
        assertThat(count(events, PipelineEvents.SDD_READY)).isEqualTo(2);
        assertThat(count(events, PipelineEvents.SDD_IMPLEMENTED)).isEqualTo(3);
        assertThat(count(events, PipelineEvents.SDD_VALIDATED)).isEqualTo(2);
        assertThat(count(events, PipelineEvents.DOCUMENT_REGISTERED)).isEqualTo(10);
        assertThat(count(events, PipelineEvents.CYCLE_CLOSED)).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.type().equals(PipelineEvents.CYCLE_CLOSED)).findFirst().orElseThrow()
                .data().path("outcome").asText()).isEqualTo("complete");
    }

    /**
     * The closing check of a scenario (RF2, RF5): the bus keeps the v1 contract; the sweeper finds no planted datum in
     * the bus, in the log of every process this scenario started, in the stores or in the spans of the given chains;
     * and, when {@code quiet}, nothing is left over. Returns the lines for the evidence file.
     */
    protected String verify(List<String> chains, boolean quiet) throws Exception {
        List<String> broken = new ArrayList<>();
        for (Spy.Event e : spy.events()) {
            if (CONTRACTS.isPipelineType(e.type())) {
                broken.addAll(CONTRACTS.violations(Spy.JSON.readTree(e.json())));
            }
        }
        Sweeper sweeper = new Sweeper(corpus(), floci, mail, Module.CHANNELS.port());
        List<Sweeper.Finding> found = new ArrayList<>();
        found.addAll(sweeper.texts("bus", spy.events().stream().map(Spy.Event::json).toList()));
        for (App app : apps) {
            found.addAll(sweeper.texts("log " + app.name(), app.lines()));
        }
        found.addAll(sweeper.stores());
        spy.settle(Duration.ofSeconds(8));      // the exporters flush every few seconds
        Map<String, Integer> spansByService = new TreeMap<>();
        Map<String, JsonNode> spans = new LinkedHashMap<>();
        for (String chain : chains) {
            for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.TARGET, Module.BUSINESS,
                    Module.ENGINEERING, Module.DELIVERY)) {
                collect(traces("sandbox-" + m.name().toLowerCase(), chain), spans);
            }
        }
        spans.values().forEach(s -> spansByService.merge(s.path("service").asText(), 1, Integer::sum));
        found.addAll(sweeper.texts("span", spans.values().stream().map(JsonNode::toString).toList()));
        List<String> leaks = found.stream().map(Sweeper.Finding::toString).toList();
        StringBuilder out = new StringBuilder();
        out.append("  events out of the contract: ").append(broken).append('\n')
                .append("  markers found (bus, spans, ").append(apps.size()).append(" process logs, stores): ")
                .append(leaks).append('\n').append("  Jaeger spans of the chain(s) by service: ").append(spansByService)
                .append('\n').append("  items the sweeper looked at, by kind of store: ").append(sweeper.coverage())
                .append('\n');
        assertThat(broken).as("events out of the v1 contract").isEmpty();
        assertThat(leaks).as("planted markers, in any form, outside the restricted blobs").isEmpty();
        if (quiet) {
            Map<String, Leftovers.Domain> left = leftovers.awaitQuiet(spy, FLOW, Leftovers.PIPELINE);
            for (Leftovers.Domain d : left.values()) {
                assertThat(d.waits()).as(d.domain() + " waits").isZero();
                assertThat(d.outbox()).as(d.domain() + " outbox").isZero();
                assertThat(d.nonTerminal()).as(d.domain() + " non-terminal instances").isEmpty();
                assertThat(d.dlqMessages()).as(d.domain() + " DLQ messages").isZero();
            }
            out.append(Leftovers.report(left));
        }
        return out.toString();
    }

    protected static boolean project(Spy.Event e, String project) {
        return project.equals(ext(e, PipelineEvents.EXT_PROJECT));
    }

    protected static String ext(Spy.Event e, String name) {
        try {
            return Spy.JSON.readTree(e.json()).path(name).asText();
        } catch (Exception ex) {
            return "";
        }
    }

    protected static long count(List<Spy.Event> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).count();
    }

    protected static String subject(Spy.Event e) {
        try {
            return Spy.JSON.readTree(e.json()).path("subject").asText();
        } catch (Exception ex) {
            return "";
        }
    }

    /** The bus of a project, one line per event, for the evidence. */
    protected static String busOf(String project, List<Spy.Event> events) {
        StringBuilder out = new StringBuilder("  bus, ").append(project).append(" (chain ")
                .append(PipelineEvents.chainIdOf(project)).append("):\n");
        events.forEach(ev -> out.append(String.format("    %-48s subject %-22s chain %s%n", ev.type(), subject(ev),
                ev.chainId())));
        return out.toString();
    }

    protected JsonNode traces(String service, String chain) {
        try {
            String tags = URLEncoder.encode("{\"stint.chainId\":\"" + chain + "\"}", StandardCharsets.UTF_8);
            return Spy.JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(Scenario.JAEGER
                    + "/api/traces?service=" + service + "&lookback=1h&limit=100&tags=" + tags)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) {
            throw new IllegalStateException("Jaeger at " + Scenario.JAEGER + " is not readable", e);
        }
    }

    /** Every span of the traces found, with its service, keyed by span id. */
    protected static void collect(JsonNode found, Map<String, JsonNode> spans) {
        for (JsonNode trace : found.path("data")) {
            for (JsonNode span : trace.path("spans")) {
                ObjectNode copy = span.deepCopy();
                copy.put("service", trace.path("processes").path(span.path("processID").asText()).path("serviceName")
                        .asText());
                spans.put(span.path("spanID").asText(), copy);
            }
        }
    }
}
