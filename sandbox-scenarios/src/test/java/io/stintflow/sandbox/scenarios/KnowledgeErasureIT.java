package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * SDD S.12, RF7, RF9 and 8f (CA6, CA8): after project p1's cycle both bases hold what they learned. A request to erase a
 * document goes through the whole path of Stint 3b.5 (find nothing to resolve, erase, verify, report) and leaves the
 * tombstone; the same request again erases nothing more; a request to erase a person (a pointer to a restricted blob,
 * never a name on the bus) ends verified, the blob forgotten and the other nodes untouched; the Business base moves to
 * another embedding model, once, and a second request for the same model changes nothing. Then the sweeper.
 */
class KnowledgeErasureIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String DESIGN = "meeting:rec-p1-c1-design";
    static final String ERASED = "io.stintflow.knowledge.erasure-completed.v1";
    static final String INCOMPLETE = "io.stintflow.knowledge.erasure-incomplete.v1";
    static final String REINDEXED = "io.stintflow.knowledge.reindexed.v1";

    @Test
    void a_document_and_a_person_are_erased_verified_and_the_business_base_changes_model() throws Exception {
        startPipeline(Map.of(
                Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        awaitClosed("p1", "1");
        spy.until("both bases indexed the design", () -> indexed("business") >= 1 && indexed("engineering") >= 1,
                Duration.ofMinutes(2));
        spy.settle(Duration.ofSeconds(5));
        StringBuilder look = new StringBuilder();
        List<String> shown = new ArrayList<>();

        // --- a document, from each base ---
        for (String[] base : new String[][] {{"business", "8097", "business-kb"}, {"engineering", "8098", "engineering-kb"}}) {
            int port = Integer.parseInt(base[1]);
            String kb = "sandbox/" + base[2];
            JsonNode before = json(port, "/graph/" + kb + "/view");
            long peopleBefore = count(before, "Person");
            assertThat(json(port, "/graph/" + kb + "/changes?after=0&limit=500&document=" + DESIGN).size())
                    .as(base[0] + " holds the design").isPositive();

            String request = "er-doc-" + base[0];
            fact("er-doc-" + base[0] + "-1", ERASED.replace("completed", "requested"), erasure(request, "document",
                    base[2]).put("documentId", DESIGN).put("label", "internal"));
            Spy.Event done = awaitFact(ERASED, request, 1);
            assertThat(done.json()).contains("\"verified\":true").contains("\"tombstone\":true");
            JsonNode after = json(port, "/graph/" + kb + "/view");
            JsonNode erase = json(port, "/graph/" + kb + "/changes?after=0&limit=500&document=" + DESIGN);
            assertThat(op(erase, "ERASE")).as(base[0] + " logs the erasure").isPositive();
            assertThat(count(after, "Person")).as("people of other documents are untouched").isLessThanOrEqualTo(peopleBefore);
            shown.add(after.toString());
            shown.add(erase.toString());

            // the same request again: a new fact, the same request id; nothing more to erase, still verified
            fact("er-doc-" + base[0] + "-2", ERASED.replace("completed", "requested"), erasure(request, "document",
                    base[2]).put("documentId", DESIGN).put("label", "internal"));
            awaitFact(ERASED, request, 2);
            assertThat(json(port, "/graph/" + kb + "/view").path("nodes").size()).as("nothing more to erase")
                    .isEqualTo(after.path("nodes").size());
            look.append(String.format("  %-12s document %s: nodes %d -> %d, people %d -> %d, erasure logged %d time(s)%n",
                    base[0], DESIGN, before.path("nodes").size(), after.path("nodes").size(), peopleBefore,
                    count(after, "Person"), op(erase, "ERASE")));
        }
        assertThat(spy.events().stream().filter(e -> e.type().equals(INCOMPLETE))).as("nothing was left behind").isEmpty();

        // --- a person: the pointer to a restricted blob, never the name on the bus ---
        String subject = "subject/er-person";
        floci.s3.putObject(r -> r.bucket("sandbox-business-blobs").key(subject), RequestBody.fromString(
                CORPUS.meeting("p1-c1-business").pii().stream().filter(p -> p.kind().equals("name")).findFirst().orElseThrow()
                        .written(),
                StandardCharsets.UTF_8));
        JsonNode beforePerson = json(8097, "/graph/sandbox/business-kb/view");
        fact("er-person-1", ERASED.replace("completed", "requested"), erasure("er-person", "person", "business-kb")
                .put("personRef", "s3://sandbox-business-blobs/" + subject).put("passages", "LINKED")
                .put("label", "restricted"));
        Spy.Event person = awaitFact(ERASED, "er-person", 1);
        // an instance of a restricted request answers by pointer (Stint 3a.5): the fact holds the pointer, the blob the counts
        assertThat(person.json()).as("a restricted fact carries no data inline").contains("\"dataref\"")
                .doesNotContain("SYNTH-PII");
        URI answer = URI.create(Spy.JSON.readTree(person.json()).path("dataref").asText());
        String answered = floci.s3.getObjectAsBytes(r -> r.bucket(answer.getHost()).key(answer.getPath().substring(1)))
                .asUtf8String();
        assertThat(answered).contains("\"verified\":true").contains("\"tombstone\":true").doesNotContain("SYNTH-PII");
        assertThatThrownBy(() -> floci.s3.getObject(r -> r.bucket("sandbox-business-blobs").key(subject)))
                .as("the subject's blob is forgotten").isInstanceOf(NoSuchKeyException.class);
        JsonNode afterPerson = json(8097, "/graph/sandbox/business-kb/view");
        assertThat(afterPerson.path("nodes").size()).as("the others stay").isEqualTo(beforePerson.path("nodes").size());
        look.append(String.format("  person erased: tombstone and verification clean, blob forgotten, business nodes %d -> %d%n",
                beforePerson.path("nodes").size(), afterPerson.path("nodes").size()));

        // --- the Business base moves to another model, once ---
        fact("em-1", "io.stintflow.knowledge.embedding-changed.v1", Spy.JSON.createObjectNode()
                .put("requestId", "em-1").put("namespace", "sandbox").put("base", "business-kb")
                .put("targetModel", "sandbox-embed-2").put("label", "internal"));
        Spy.Event moved = awaitFact(REINDEXED, "em-1", 1);
        assertThat(data(moved, "status")).isEqualTo("APPLIED");
        fact("em-2", "io.stintflow.knowledge.embedding-changed.v1", Spy.JSON.createObjectNode()
                .put("requestId", "em-2").put("namespace", "sandbox").put("base", "business-kb")
                .put("targetModel", "sandbox-embed-2").put("label", "internal"));
        assertThat(data(awaitFact(REINDEXED, "em-2", 1), "status")).isEqualTo("UNCHANGED");
        JsonNode changes = json(8097, "/graph/sandbox/business-kb/changes?after=0&limit=500");
        assertThat(op(changes, "REINDEX")).as("one entry for the move").isEqualTo(1);
        assertThat(json(8097, "/graph/sandbox/business-kb/view").path("nodes").size()).isEqualTo(
                afterPerson.path("nodes").size());
        shown.add(changes.toString());
        look.append(String.format("  business model moved: %s then %s; REINDEX logged %d time(s)%n", data(moved, "status"),
                "UNCHANGED", op(changes, "REINDEX")));

        assertThat(new Sweeper(corpus(), floci, mail, Module.CHANNELS.port()).texts("inspector", shown)).isEmpty();
        String closing = verify(List.of(PipelineEvents.chainIdOf("p1")), true);
        Scenario.evidence("S.12-CA6-8.txt", "S.12 CA6 and CA8: erasure of a document and of a person, and the change of model\n"
                + timeline() + look + closing);
    }

    // --- helpers ---

    private int indexed(String domain) {
        return (int) spy.events().stream().filter(e -> e.type().equals("io.sandbox." + domain + ".document-indexed.v1"))
                .count();
    }

    private static ObjectNode erasure(String requestId, String kind, String base) {
        return Spy.JSON.createObjectNode().put("requestId", requestId).put("kind", kind).put("namespace", "sandbox")
                .put("base", base);
    }

    /** A fact from the data-protection channel, put on the domain bus as the portal and the domains do. */
    private void fact(String id, String type, ObjectNode data) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", id).put("type", type)
                .put("source", "https://sandbox.stintflow.io/data-protection").put("datacontenttype", "application/json");
        event.set("data", data);
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/data-protection").detailType(type).detail(detail).build()));
        note("fact %s %s", type.substring(type.lastIndexOf('.', type.length() - 4) + 1), id);
    }

    /** The {@code nth} event of {@code type} for {@code requestId}. */
    private Spy.Event awaitFact(String type, String requestId, int nth) {
        try {
            spy.until(type + " for " + requestId + " (" + nth + ")", () -> matching(type, requestId).size() >= nth
                    || !matching(INCOMPLETE, requestId).isEmpty(), Duration.ofMinutes(3));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; knowledge events seen: " + spy.events().stream()
                    .filter(ev -> ev.type().startsWith("io.stintflow.knowledge")).map(ev -> ev.type() + " "
                            + ev.json()).toList(), e);
        }
        assertThat(matching(INCOMPLETE, requestId).stream().map(Spy.Event::json).toList())
                .as("the erasure of " + requestId + " left something behind").isEmpty();
        return matching(type, requestId).get(nth - 1);
    }

    private List<Spy.Event> matching(String type, String requestId) {
        return spy.events().stream().filter(e -> e.type().equals(type) && (requestId.equals(data(e, "requestId"))
                || requestId.equals(subject(e)))).toList();
    }

    private JsonNode json(int port, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }

    private static long count(JsonNode view, String type) {
        long n = 0;
        for (JsonNode node : view.path("nodes")) {
            if (type.equals(node.path("type").asText())) {
                n++;
            }
        }
        return n;
    }

    private static long op(JsonNode changes, String op) {
        long n = 0;
        for (JsonNode change : changes) {
            if (op.equals(change.path("op").asText())) {
                n++;
            }
        }
        return n;
    }

    private static String data(Spy.Event e, String field) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path(field).asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
