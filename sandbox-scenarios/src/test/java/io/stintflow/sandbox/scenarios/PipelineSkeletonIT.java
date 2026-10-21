package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Leftovers;
import io.stintflow.sandbox.scenarios.support.MailHog;
import io.stintflow.sandbox.scenarios.support.Portal;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.5, CA2 (8h): the meeting pipeline's skeleton on floci, everything fake, seven processes, watched only from
 * outside. Project p1: a business meeting is recorded -> Business -> design-published -> Engineering ->
 * sdd-batch-approved -> Delivery, two SDDs in two instances (one fails its first attempt) -> cycle-closed. Project
 * p2: the technical meeting has a blocking question -> questions-sent and its e-mail -> cycle 2 -> cycle-closed. The
 * scripted reviewer approves through the portal with each role's test credential. Then: one chain per project on the
 * bus and in Jaeger (spans of every service, linked by FOLLOWS_FROM), every event keeps the v1 contract and holds no
 * marker in any form, the counts, and nothing left over.
 */
class PipelineSkeletonIT extends ScenarioBase {

    static final Duration CYCLE = Duration.ofMinutes(6);
    static final Corpus CORPUS = Corpus.load(Scenario.root().resolve("corpus"));
    static final PipelineContracts CONTRACTS = new PipelineContracts();
    static final String[] ROLES = {PipelineEvents.ROLE_BUSINESS, PipelineEvents.ROLE_TECH, PipelineEvents.ROLE_TECHNICIAN};

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> answered = new ArrayList<>();
    String coverage = "";

    @Test
    void two_projects_go_through_the_three_domains_each_on_one_chain() throws Exception {
        Map<String, App> running = new LinkedHashMap<>();
        for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.TARGET, Module.BUSINESS,
                Module.ENGINEERING, Module.DELIVERY)) {
            running.put(m.name(), start(m.name().toLowerCase(), m, Map.of()));
        }
        Portal portal = new Portal(Module.CHANNELS.port());
        portal.rejectFirstApprovalOf("p2-c1-design");   // SDD S.8, CA7: p2's first design is rejected once, then approved

        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        note("recorded p2-c1-business");
        awaitClosed(portal, "p1", "1");
        awaitClosed(portal, "p2", "2");
        Map<String, Leftovers.Domain> left = leftovers.awaitQuiet(spy, FLOW, Leftovers.PIPELINE);
        spy.settle(Duration.ofSeconds(3));

        // --- one chain per project, on the bus ---
        String p1 = PipelineEvents.chainIdOf("p1");
        String p2 = PipelineEvents.chainIdOf("p2");
        List<Spy.Event> ofP1 = spy.events().stream().filter(e -> project(e, "p1")).toList();
        List<Spy.Event> ofP2 = spy.events().stream().filter(e -> project(e, "p2")).toList();
        assertThat(ofP1).isNotEmpty().allMatch(e -> p1.equals(e.chainId()), "on the chain of p1");
        assertThat(ofP2).isNotEmpty().allMatch(e -> p2.equals(e.chainId()), "on the chain of p2");
        assertThat(spy.events()).filteredOn(e -> e.type().startsWith(PipelineEvents.REVIEW_PREFIX))
                .allMatch(e -> p1.equals(e.chainId()) || p2.equals(e.chainId()), "reviews on their project's chain");

        // --- the counts of p1 ---
        assertThat(count(ofP1, PipelineEvents.MEETING_RECORDED)).isEqualTo(2);
        assertThat(count(ofP1, PipelineEvents.DESIGN_PUBLISHED)).isEqualTo(1);
        assertThat(count(ofP1, PipelineEvents.REFINEMENT_REQUESTED)).isEqualTo(1);
        assertThat(count(ofP1, PipelineEvents.SDD_BATCH_APPROVED)).isEqualTo(1);
        assertThat(count(ofP1, PipelineEvents.SDD_READY)).isEqualTo(2);
        assertThat(count(ofP1, PipelineEvents.SDD_IMPLEMENTED)).isEqualTo(3);
        assertThat(count(ofP1, PipelineEvents.SDD_VALIDATED)).isEqualTo(2);
        assertThat(count(ofP1, PipelineEvents.DOCUMENT_REGISTERED)).isEqualTo(4);   // design, batch, two SDDs
        assertThat(single(ofP1, PipelineEvents.CYCLE_CLOSED).data().path("outcome").asText()).isEqualTo("complete");
        // --- p2: questions, the restart, the second cycle ---
        assertThat(count(ofP2, PipelineEvents.QUESTIONS_SENT)).isEqualTo(1);
        assertThat(single(ofP2, PipelineEvents.QUESTIONS_SENT).data().path("nextCycle").asInt()).isEqualTo(2);
        assertThat(count(ofP2, PipelineEvents.MEETING_RECORDED)).isEqualTo(4);
        assertThat(count(ofP2, PipelineEvents.DESIGN_PUBLISHED)).isEqualTo(2);
        assertThat(count(ofP2, PipelineEvents.CYCLE_CLOSED)).isEqualTo(1);
        assertThat(mail.all()).extracting(MailHog.Mail::subject).contains("[p2 c1] Questions from engineering",
                "[p1 c1] Technical refinement invitation", "[p1 c1] Cycle closed");
        assertThat(portal.pages()).hasSize(1);      // p1 publishes on Confluence; p2's channel is e-mail

        // --- the register (S.7): every document of the cycle is in the catalog, on its project's chain ---
        List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> catalog = floci.ddb
                .scan(r -> r.tableName("records-catalog").consistentRead(true)).items();
        List<String> registeredOnBus = spy.events().stream().filter(e -> e.type().equals(PipelineEvents.DOCUMENT_REGISTERED))
                .map(PipelineSkeletonIT::subject).distinct().sorted().toList();
        List<String> inCatalog = catalog.stream().map(i -> i.get("recordId").s()).distinct().sorted().toList();
        assertThat(inCatalog).as("the catalog holds the documents the bus announced").isEqualTo(registeredOnBus);
        StringBuilder register = new StringBuilder();
        for (var item : catalog) {
            String project = item.get("projectId").s();
            register.append(String.format("    %-34s v%s %-10s project %s chain %s meeting %s cause %s supersedes %s%n",
                    item.get("recordId").s(), item.get("version").n(), item.get("kind").s(), project,
                    item.get("chainId") == null ? "-" : item.get("chainId").s(),
                    item.get("sourceMeetingId") == null ? "-" : item.get("sourceMeetingId").s(),
                    item.get("causationId") == null ? "-" : item.get("causationId").s(),
                    item.get("supersedes") == null ? "-" : item.get("supersedes").n()));
            assertThat(item.get("chainId")).as(item.get("recordId").s() + " has a chain").isNotNull();
            assertThat(item.get("chainId").s()).as(item.get("recordId").s() + " is on its project's chain")
                    .isEqualTo(PipelineEvents.chainIdOf(project));
            if (List.of("design", "questions").contains(item.get("kind").s())) {
                assertThat(item.get("sourceMeetingId")).as(item.get("recordId").s() + " names its meeting").isNotNull();
            }
        }
        // SDD S.8, CA7: the rejected design is a second version of the same document, and it supersedes the first
        var reworked = catalog.stream().filter(i -> i.get("recordId").s().equals("rec-p2-c1-design")).toList();
        assertThat(reworked).as("p2's design has two versions").extracting(i -> i.get("version").n())
                .containsExactlyInAnyOrder("1", "2");
        assertThat(reworked.stream().filter(i -> i.get("version").n().equals("2")).findFirst().orElseThrow()
                .get("supersedes").n()).isEqualTo("1");
        Scenario.evidence("S.8-CA7.txt", "S.8 CA7: p2's first design was rejected once and rewritten as version 2\n"
                + register);
        Scenario.evidence("S.7-CA6.txt", "S.7 CA6: the register after the pipeline's skeleton (" + catalog.size()
                + " versions, " + inCatalog.size() + " documents; the bus announced the same documents)\n" + register);

        // --- the contract of every event of the bus, and the sweep (S.6): logs, bus, e-mails, pages, buckets, tables ---
        List<String> broken = new ArrayList<>();
        for (Spy.Event e : spy.events()) {
            if (CONTRACTS.isPipelineType(e.type())) {
                broken.addAll(CONTRACTS.violations(Spy.JSON.readTree(e.json())));
            }
        }
        Sweeper sweeper = new Sweeper(CORPUS, floci, mail, Module.CHANNELS.port());
        List<Sweeper.Finding> found = new ArrayList<>();
        found.addAll(sweeper.texts("bus", spy.events().stream().map(Spy.Event::json).toList()));
        for (App app : running.values()) {
            found.addAll(sweeper.texts("log " + app.name(), app.lines()));
        }
        found.addAll(sweeper.stores());
        assertThat(broken).as("events out of the v1 contract").isEmpty();

        // --- Jaeger: the spans of p1's chain, in every service, linked across services ---
        Map<String, Integer> spansByService = new TreeMap<>();
        List<String> links = new ArrayList<>();
        Map<String, JsonNode> spans = new LinkedHashMap<>();
        spy.until("the spans of p1 in Jaeger for every domain and service", () -> {
            spans.clear();
            for (Module m : running.values().stream().map(App::module).toList()) {
                collect(traces("sandbox-" + m.name().toLowerCase(), p1), spans);
            }
            spansByService.clear();
            spans.values().forEach(s -> spansByService.merge(s.path("service").asText(), 1, Integer::sum));
            return spansByService.keySet().containsAll(List.of("sandbox-business", "sandbox-engineering",
                    "sandbox-delivery", "sandbox-privacy", "sandbox-records", "sandbox-channels", "sandbox-target"));
        }, Duration.ofSeconds(90));
        for (JsonNode s : spans.values()) {
            for (JsonNode ref : s.path("references")) {
                JsonNode target = spans.get(ref.path("spanID").asText());
                if ("FOLLOWS_FROM".equals(ref.path("refType").asText()) && target != null
                        && !target.path("service").asText().equals(s.path("service").asText())) {
                    links.add(s.path("service").asText() + " " + s.path("operationName").asText() + " FOLLOWS_FROM "
                            + target.path("service").asText() + " " + target.path("operationName").asText());
                }
            }
        }
        found.addAll(sweeper.texts("span", spans.values().stream().map(JsonNode::toString).toList()));
        List<String> leaks = found.stream().map(Sweeper.Finding::toString).toList();

        coverage = sweeper.coverage();
        evidence(running, left, ofP1, ofP2, spansByService, links, leaks, broken);
        assertThat(leaks).as("planted markers, in any form, outside the restricted blobs").isEmpty();
        assertThat(links).as("cross-service FOLLOWS_FROM links on p1's chain")
                .anyMatch(l -> l.startsWith("sandbox-engineering") && l.contains("FOLLOWS_FROM sandbox-business"))
                .anyMatch(l -> l.startsWith("sandbox-delivery") && l.contains("FOLLOWS_FROM sandbox-engineering"));
        for (Leftovers.Domain d : left.values()) {
            assertThat(d.waits()).as(d.domain() + " waits").isZero();
            assertThat(d.outbox()).as(d.domain() + " outbox").isZero();
            assertThat(d.nonTerminal()).as(d.domain() + " non-terminal instances").isEmpty();
            assertThat(d.dlqMessages()).as(d.domain() + " DLQ messages").isZero();
        }
        // The inbox holds only (a) review answers kept for the review's later phases (reminder, escalation): Stint keys
        // each phase apart and keeps an answer for the ones not waiting yet; and (b) an SDD's end that reached the
        // Delivery's coordinator while it was asking check-sdds, not listening. Both expire (inbox TTL, 15 min at most).
        for (String domain : Leftovers.PIPELINE) {
            List<String> kept = new ArrayList<>();
            floci.ddb.scan(r -> r.tableName(domain + "-inbox").consistentRead(true)).items()
                    .forEach(i -> kept.add(i.get("waitKey").s()));
            assertThat(kept).as(domain + " inbox").allMatch(k -> k.startsWith("event:" + PipelineEvents.REVIEW_COMPLETED)
                    || k.startsWith("event:" + PipelineEvents.SDD_VALIDATED)
                    || k.startsWith("event:" + PipelineEvents.SDD_CANCELLED));
        }
    }

    /** Answers reviews as the corpus key between long polls, until the project's cycle closed. */
    void awaitClosed(Portal portal, String project, String cycle) {
        spy.until(project + " cycle " + cycle + " closed", () -> {
            portal.answerAsTheKey(CORPUS, ROLES).forEach(a -> {
                answered.add(a);
                note("answered %s", a);
            });
            return spy.events().stream().anyMatch(e -> e.type().equals(PipelineEvents.CYCLE_CLOSED)
                    && project(e, project) && cycle.equals(ext(e, PipelineEvents.EXT_CYCLE)));
        }, CYCLE);
        note("%s cycle %s closed", project, cycle);
    }

    static boolean project(Spy.Event e, String project) {
        return project.equals(ext(e, PipelineEvents.EXT_PROJECT));
    }

    static String ext(Spy.Event e, String name) {
        try {
            return Spy.JSON.readTree(e.json()).path(name).asText();
        } catch (Exception ex) {
            return "";
        }
    }

    static long count(List<Spy.Event> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).count();
    }

    static Spy.Event single(List<Spy.Event> events, String type) {
        List<Spy.Event> found = events.stream().filter(e -> e.type().equals(type)).toList();
        assertThat(found).as(type).hasSize(1);
        return found.getFirst();
    }

    JsonNode traces(String service, String chain) {
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
    static void collect(JsonNode found, Map<String, JsonNode> spans) {
        for (JsonNode trace : found.path("data")) {
            for (JsonNode span : trace.path("spans")) {
                ObjectNode copy = span.deepCopy();
                copy.put("service", trace.path("processes").path(span.path("processID").asText()).path("serviceName")
                        .asText());
                spans.put(span.path("spanID").asText(), copy);
            }
        }
    }

    void evidence(Map<String, App> running, Map<String, Leftovers.Domain> left, List<Spy.Event> ofP1,
            List<Spy.Event> ofP2, Map<String, Integer> spansByService, List<String> links, List<String> leaks,
            List<String> broken) throws Exception {
        StringBuilder e = new StringBuilder("S.5 CA2: the pipeline's skeleton on floci, everything fake\n")
                .append(timeline()).append("  reviews answered through the portal:\n");
        answered.forEach(a -> e.append("    ").append(a).append('\n'));
        for (var project : Map.of("p1", ofP1, "p2", ofP2).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            e.append("  bus, ").append(project.getKey()).append(" (chain ")
                    .append(PipelineEvents.chainIdOf(project.getKey())).append("):\n");
            project.getValue().forEach(ev -> e.append(String.format("    %-48s subject %-22s chain %s%n", ev.type(),
                    subject(ev), ev.chainId())));
        }
        e.append("  Jaeger spans on p1's chain by service: ").append(spansByService).append('\n')
                .append("  cross-service FOLLOWS_FROM links (").append(links.size()).append("):\n");
        links.stream().distinct().sorted().forEach(l -> e.append("    ").append(l).append('\n'));
        e.append("  events out of the contract: ").append(broken).append('\n')
                .append("  markers found (bus, spans, ").append(running.size()).append(" process logs): ")
                .append(leaks).append('\n').append("  items the sweeper looked at, by kind of store: ").append(coverage)
                .append('\n').append("  e-mails:\n").append(mails(mail.all()))
                .append(Leftovers.report(left));
        Scenario.evidence("S.5-CA2.txt", e.toString());
        Files.writeString(Scenario.out().resolve("S.5-CA2-spy.json"), Spy.JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(spy.events().stream().map(ev -> {
                    try {
                        return Spy.JSON.readTree(ev.json());
                    } catch (Exception ex) {
                        return null;
                    }
                }).toList()));
        note("evidence written at %s", Instant.now());
    }

    static String subject(Spy.Event e) {
        try {
            return Spy.JSON.readTree(e.json()).path("subject").asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
