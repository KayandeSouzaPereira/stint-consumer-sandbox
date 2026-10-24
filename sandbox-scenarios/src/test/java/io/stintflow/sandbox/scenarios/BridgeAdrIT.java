package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.MailHog;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * SDD S.14, CA4 and CA5: after project p2's two cycles Business holds decisions and a requirement and Engineering holds
 * pointers to them. Three architecture sessions are drafted against them. One is compatible: its ADR is written with the
 * pointers that motivate it and the verdict, and published. One conflicts and the technical representative keeps the
 * conflict: its ADR is published as conflicting, and the questions go to Business (registered, e-mailed, questions-sent).
 * One conflicts and the representative settles it as compatible: its ADR is proposed and nothing is asked of Business.
 */
class BridgeAdrIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String DRAFTED = "io.sandbox.engineering.architecture-decisions-drafted.v1";
    static final String PUBLISHED = "io.sandbox.engineering.adrs-published.v1";
    static final String QUESTIONS = PipelineEvents.QUESTIONS_SENT;

    @Test
    void adrs_are_traceable_to_business_decisions_and_a_kept_conflict_goes_back_to_business() throws Exception {
        startPipeline(Map.of(
                Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        portal.rejectItemOnce("p2-c2-batch", "p2-sdd-1");
        portal.rejectFirstApprovalOf("p2-c1-design");
        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        awaitClosed("p2", "2");
        spy.until("the pointers resolved", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.engineering.pointers-resolved.v1")), Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(5));
        long questionsBefore = spy.events().stream().filter(e -> e.type().equals(QUESTIONS)).count();

        KnowledgeCorpus kb = KnowledgeCorpus.load(Scenario.root().resolve("corpus"));
        Map<String, String> pointerOfKey = pointersByKey(kb);
        StringBuilder look = new StringBuilder();

        // --- a-001: compatible ---
        draft("a-001", "a-001", kb, pointerOfKey);
        Spy.Event compatible = awaitPublished("a-001", Duration.ofMinutes(4), "tech-rep", "compatible");
        JsonNode counts = Spy.JSON.readTree(compatible.json()).path("data").path("counts");
        assertThat(counts.path("decisions").asInt()).isEqualTo(1);
        assertThat(counts.path("conflicting").asInt()).isZero();
        look.append("  a-001 (compatible): one ADR, proposed, published\n");

        // --- a-002: conflicts, and the representative keeps it ---
        draft("a-002", "a-002", kb, pointerOfKey);
        Spy.Event conflicting = awaitPublished("a-002", Duration.ofMinutes(4), "tech-rep", "conflicts");
        assertThat(Spy.JSON.readTree(conflicting.json()).path("data").path("counts").path("conflicting").asInt()).isEqualTo(1);
        spy.until("the questions went to Business", () -> spy.events().stream().filter(e -> e.type().equals(QUESTIONS))
                .count() > questionsBefore, Duration.ofMinutes(2));
        Spy.Event sent = spy.events().stream().filter(e -> e.type().equals(QUESTIONS)).reduce((a, b) -> b).orElseThrow();
        assertThat(Spy.JSON.readTree(sent.json()).path("data").path("nextCycle").asInt()).isEqualTo(3);
        spy.until("the e-mail of doubts", () -> mail.all().stream().filter(m -> m.subject().contains("Questions from engineering"))
                .count() >= 2, Duration.ofMinutes(2));
        look.append("  a-002 (conflicts, kept): ADR published as conflicting; questions registered and sent, next cycle 3;"
                + " e-mail to Business\n");

        // --- a-003: the same conflict, settled as compatible by the representative ---
        draft("a-003", "a-002", kb, pointerOfKey);
        long sentBefore = spy.events().stream().filter(e -> e.type().equals(QUESTIONS)).count();
        Spy.Event settled = awaitPublished("a-003", Duration.ofMinutes(4), "tech-rep", "compatible");
        assertThat(Spy.JSON.readTree(settled.json()).path("data").path("counts").path("conflicting").asInt()).isZero();
        spy.settle(Duration.ofSeconds(8));
        assertThat(spy.events().stream().filter(e -> e.type().equals(QUESTIONS)).count())
                .as("a conflict the representative settles asks nothing of Business").isEqualTo(sentBefore);
        look.append("  a-003 (conflict settled as compatible): ADR proposed; nothing asked of Business\n");

        // --- the ADRs on their pages and in their documents: traceable, and nothing of the business decisions' content ---
        JsonNode pages = portal.pages();
        List<String> titles = new ArrayList<>();
        pages.forEach(p -> titles.add(p.path("title").asText()));
        assertThat(titles).as("one page for each ADR").anyMatch(t -> t.startsWith("ADR adr-limit"))
                .anyMatch(t -> t.startsWith("ADR adr-late"));
        List<String> documents = adrDocuments();
        assertThat(documents).hasSize(3);
        List<String> secrets = new ArrayList<>();
        kb.decisions().forEach(d -> secrets.add(d.summary()));
        kb.requirements().forEach(r -> secrets.add(r.summary()));
        for (String document : documents) {
            assertThat(document).as("motivated by").contains("stint-kg://sandbox/business-kb/");
            for (String secret : secrets) {
                assertThat(document).as("the ADR holds the pointers and the verdict, not what Business decided")
                        .doesNotContain(secret);
            }
        }
        assertThat(documents.stream().filter(d -> d.contains("conflicting")).count()).isEqualTo(1);
        look.append("  ADR documents: ").append(documents.size()).append(", each with its pointers and its verdict\n");

        List<String> shown = new ArrayList<>(documents);
        shown.add(pages.toString());
        mail.all().forEach(m -> shown.add(m.subject() + " " + m.body()));
        assertThat(new Sweeper(corpus(), floci, mail, Module.CHANNELS.port()).texts("adr", shown)).isEmpty();
        String closing = verify(List.of(PipelineEvents.chainIdOf("p2")), true);
        Scenario.evidence("S.14-CA4-5.txt", "S.14 CA4 and CA5: three architecture sessions against what Business decided\n"
                + timeline() + look + closing);
    }

    // --- helpers ---

    /** The pointer, as Engineering holds it, of each decision or requirement of the answer key, found by its label. */
    private Map<String, String> pointersByKey(KnowledgeCorpus kb) throws Exception {
        JsonNode view = business("/graph/sandbox/business-kb/view");
        JsonNode changes = business("/graph/sandbox/business-kb/changes?after=0&limit=500");
        Map<String, String> out = new java.util.HashMap<>();
        List<String[]> keyed = new ArrayList<>();
        kb.decisions().forEach(d -> keyed.add(new String[] {d.key(), d.label()}));
        kb.requirements().forEach(r -> keyed.add(new String[] {r.key(), r.label()}));
        for (String[] k : keyed) {
            for (JsonNode node : view.path("nodes")) {
                if (k[1].equals(node.path("label").asText())) {
                    long version = 0;
                    for (JsonNode change : changes) {
                        if (node.path("id").asText().equals(change.path("targetId").asText())) {
                            version = Math.max(version, change.path("sequence").asLong());
                        }
                    }
                    out.put(k[0], "stint-kg://sandbox/business-kb/" + node.path("id").asText() + "?v=" + version);
                }
            }
        }
        assertThat(out.keySet()).as("Business holds each decision and requirement of the answer key")
                .containsAll(keyed.stream().map(k -> k[0]).toList());
        return out;
    }

    /** Writes the draft of {@code session} (the answer key's) as a blob and announces it with the fact. */
    private void draft(String sessionId, String keySession, KnowledgeCorpus kb, Map<String, String> pointers) throws Exception {
        KnowledgeCorpus.Architecture session = kb.architecture().stream().filter(a -> a.id().equals(keySession)).findFirst()
                .orElseThrow();
        ObjectNode draft = Spy.JSON.createObjectNode();
        ArrayNode decisions = draft.putArray("decisions");
        for (KnowledgeCorpus.Draft d : session.drafts()) {
            ObjectNode one = decisions.addObject().put("id", d.id() + (sessionId.equals(keySession) ? "" : "-" + sessionId))
                    .put("title", d.title()).put("summary", d.summary());
            ArrayNode motivated = one.putArray("motivatedBy");
            d.motivatedBy().forEach(key -> motivated.add(pointers.get(key)));
        }
        String key = "adr-draft/" + sessionId + ".json";
        floci.s3.putObject(r -> r.bucket("sandbox-engineering-blobs").key(key), RequestBody.fromBytes(
                Spy.JSON.writeValueAsString(draft).getBytes(StandardCharsets.UTF_8)));
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", sessionId + ":drafted")
                .put("type", DRAFTED).put("source", "https://sandbox.stintflow.io/engineering").put("subject", sessionId)
                .put("datacontenttype", "application/json").put("sandboxproject", "p2").put("sandboxcycle", "2")
                .put("chainid", PipelineEvents.chainIdOf("p2"));
        event.putObject("data").put("sessionId", sessionId).put("draftRef", "s3://sandbox-engineering-blobs/" + key)
                .put("label", "confidential");
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/engineering").detailType(DRAFTED).detail(detail).build()));
        note("drafted %s", sessionId);
    }

    /** Waits for the ADRs of the session to be published, answering the conflict reviews with {@code answer}. */
    private Spy.Event awaitPublished(String sessionId, Duration within, String role, String answer) {
        spy.until("ADRs of " + sessionId, () -> {
            for (JsonNode review : portal.open(role)) {
                String id = review.path("reviewId").asText();
                boolean conflictQuestion = "choice".equals(review.path("kind").asText())
                        && review.toString().contains("conflicts") && !answered.contains(id);
                if (conflictQuestion) {
                    ObjectNode body = Spy.JSON.createObjectNode().put("value", answer);
                    if (portal.answer(role, id, body) == 202) {
                        answered.add(id);
                        note("answered %s with %s", id, answer);
                    }
                }
            }
            return spy.events().stream().anyMatch(e -> e.type().equals(PUBLISHED) && sessionId.equals(subject(e)));
        }, within);
        return spy.events().stream().filter(e -> e.type().equals(PUBLISHED) && sessionId.equals(subject(e))).findFirst()
                .orElseThrow();
    }

    /** The documents write-adrs wrote, read from Engineering's blobs. */
    private List<String> adrDocuments() {
        List<String> out = new ArrayList<>();
        floci.s3.listObjectsV2(r -> r.bucket("sandbox-engineering-blobs").prefix("retention/adr-document/")).contents()
                .forEach(o -> out.add(floci.s3.getObjectAsBytes(r -> r.bucket("sandbox-engineering-blobs").key(o.key()))
                        .asUtf8String()));
        return out;
    }

    private JsonNode business(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8197" + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }
}
