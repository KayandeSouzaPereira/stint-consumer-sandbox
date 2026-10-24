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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * SDD S.14, CA2, CA3 (policy) and CA8: after p2's cycle Engineering has asked Business what its references point at. Business
 * answers through the view its contract publishes: the labels and the status of a decision, never its summary; the answer
 * rests in a blob that expires and nowhere in Engineering. A pointer to a node of a type the contract does not grant is
 * DENIED, and the answer shows nothing of it; a pointer to a node erased since is REMOVED; and erasing it in Business does not
 * reach the register's copy, which belongs to another domain.
 */
class BridgePolicyIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String RESOLVED = "io.sandbox.engineering.pointers-resolved.v1";
    static final String LINKED = "io.sandbox.engineering.references-linked.v1";

    @Test
    void business_answers_by_its_contract_and_engineering_keeps_none_of_it() throws Exception {
        startPipeline(Map.of(
                Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR),
                Module.ENGINEERING, Map.of("engineering.knowledge.operators", OPERATOR)));
        portal.rejectItemOnce("p2-c2-batch", "p2-sdd-1");
        portal.rejectFirstApprovalOf("p2-c1-design");
        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        awaitClosed("p2", "2");
        spy.until("the pointers resolved", () -> !events(RESOLVED).isEmpty(), Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(8));

        KnowledgeCorpus kb = KnowledgeCorpus.load(Scenario.root().resolve("corpus"));
        List<String> summaries = new ArrayList<>();
        kb.decisions().forEach(d -> summaries.add(d.summary()));
        kb.requirements().forEach(r -> summaries.add(r.summary()));

        // --- RESOLVED: the view of the contract, in a blob that expires ---
        StringBuilder look = new StringBuilder();
        int resolved = 0;
        List<String> views = new ArrayList<>();
        for (Spy.Event event : events(RESOLVED)) {
            JsonNode data = Spy.JSON.readTree(event.json()).path("data");
            assertThat(data.path("resolution").asText()).isEqualTo("resolved");
            assertThat(data.path("counts").path("DENIED").asInt()).isZero();
            assertThat(data.path("counts").path("REMOVED").asInt()).isZero();
            resolved += data.path("counts").path("RESOLVED").asInt();
            URI viewRef = URI.create(data.path("viewRef").asText());
            assertThat(viewRef.getHost()).as("the answer rests in Business's own bucket").isEqualTo("sandbox-business-blobs");
            assertThat(viewRef.getPath()).contains("resolved-view");
            String view = floci.s3.getObjectAsBytes(r -> r.bucket(viewRef.getHost()).key(viewRef.getPath().substring(1)))
                    .asUtf8String();
            views.add(view);
            assertThat(view).as("the label and the status are in the view").contains("Limit per member");
            for (String summary : summaries) {
                assertThat(view).as("the summary is not in the view of the contract").doesNotContain(summary);
            }
        }
        assertThat(resolved).isPositive();
        look.append("  resolved ").append(resolved).append(" pointer(s) through the view; summaries in the view: none\n");

        // --- DENIED: a node of a type the contract does not grant ---
        JsonNode view = bus("8197", "/graph/sandbox/business-kb/view");
        JsonNode changes = bus("8197", "/graph/sandbox/business-kb/changes?after=0&limit=500");
        String term = null;
        long version = 0;
        for (JsonNode node : view.path("nodes")) {
            if ("Term".equals(node.path("type").asText())) {
                term = node.path("id").asText();
            }
        }
        assertThat(term).as("Business holds a glossary term").isNotNull();
        for (JsonNode change : changes) {
            if (term.equals(change.path("targetId").asText())) {
                version = Math.max(version, change.path("sequence").asLong());
            }
        }
        fact("denied-1", pointerFact("forged-denied", "stint-kg://sandbox/business-kb/" + term + "?v=" + version));
        Spy.Event denied = awaitResolved("forged-denied");
        JsonNode deniedData = Spy.JSON.readTree(denied.json()).path("data");
        assertThat(deniedData.path("counts").path("DENIED").asInt()).isEqualTo(1);
        assertThat(deniedData.path("counts").path("RESOLVED").asInt()).isZero();
        URI deniedView = URI.create(deniedData.path("viewRef").asText());
        String deniedText = floci.s3.getObjectAsBytes(r -> r.bucket(deniedView.getHost()).key(deniedView.getPath().substring(1)))
                .asUtf8String();
        JsonNode entry = Spy.JSON.readTree(deniedText).path("entries").get(0);
        assertThat(entry.fieldNames()).as("a node that is not granted shows only that it was refused, as the caller asked")
                .toIterable().containsOnly("pointer", "status");
        assertThat(entry.path("pointer").asText()).isEqualTo("stint-kg://sandbox/business-kb/" + term + "?v=" + version);
        look.append("  a pointer to a Term (not granted): DENIED, nothing of it in the answer\n");

        // --- REMOVED: the document the decisions came from is erased in Business ---
        List<String> pointers = new ArrayList<>();
        for (Spy.Event event : events("io.sandbox.business.nodes-published.v1")) {
            Spy.JSON.readTree(event.json()).path("data").path("items").forEach(i -> pointers.add(i.path("pointer").asText()));
        }
        assertThat(pointers).isNotEmpty();
        URI register = storedRef("rec-p2-c2-design");
        assertThat(exists(register)).isTrue();
        erase("ex-p2");
        spy.until("the erasure completed", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.stintflow.knowledge.erasure-completed.v1") && "ex-p2".equals(subject(e))), Duration.ofMinutes(3));
        fact("removed-1", pointerFact("forged-removed", pointers.toArray(String[]::new)));
        JsonNode removed = Spy.JSON.readTree(awaitResolved("forged-removed").json()).path("data").path("counts");
        assertThat(removed.path("REMOVED").asInt()).as("the nodes of the erased document").isPositive();
        assertThat(removed.path("RESOLVED").asInt()).isZero();
        assertThat(exists(register)).as("another domain's copy of the document is untouched (S13-1)").isTrue();
        look.append("  after the erasure in Business: REMOVED ").append(removed.path("REMOVED").asInt())
                .append("; the register's copy of the design is still there\n");

        // --- nothing of Business's content in Engineering ---
        String engineering = bus("8199", "/graph/sandbox/engineering-arch/view").toString()
                + bus("8199", "/graph/sandbox/engineering-arch/changes?after=0&limit=500")
                + bus("8198", "/graph/sandbox/engineering-kb/view");
        for (String summary : summaries) {
            assertThat(engineering).doesNotContain(summary);
        }
        List<String> shown = new ArrayList<>(views);
        shown.add(engineering);
        assertThat(new Sweeper(corpus(), floci, mail, Module.CHANNELS.port()).texts("bridge", shown)).isEmpty();
        String closing = verify(List.of(PipelineEvents.chainIdOf("p2")), true);
        Scenario.evidence("S.14-CA2-3-8.txt", "S.14 CA2, CA3 and CA8: resolution by the contract of Business\n" + timeline()
                + look + closing);
    }

    // --- helpers ---

    private List<Spy.Event> events(String type) {
        return spy.events().stream().filter(e -> e.type().equals(type)).toList();
    }

    private Spy.Event awaitResolved(String documentId) {
        spy.until("resolved " + documentId, () -> events(RESOLVED).stream().anyMatch(e -> documentId.equals(subject(e))),
                Duration.ofMinutes(3));
        return events(RESOLVED).stream().filter(e -> documentId.equals(subject(e))).findFirst().orElseThrow();
    }

    private ObjectNode pointerFact(String documentId, String... pointers) {
        ObjectNode data = Spy.JSON.createObjectNode().put("documentId", documentId).put("status", "APPLIED")
                .put("label", "confidential");
        ArrayNode list = data.putArray("pointers");
        for (String pointer : pointers) {
            list.add(pointer);
        }
        return data;
    }

    private void fact(String id, ObjectNode data) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", id).put("type", LINKED)
                .put("source", "https://sandbox.stintflow.io/engineering").put("datacontenttype", "application/json")
                .put("subject", data.path("documentId").asText()).put("sandboxproject", "forged").put("sandboxcycle", "1");
        event.set("data", data);
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/engineering").detailType(LINKED).detail(detail).build()));
    }

    private void erase(String id) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", id + "-fact")
                .put("type", "io.stintflow.knowledge.erasure-requested.v1")
                .put("source", "https://sandbox.stintflow.io/data-protection").put("datacontenttype", "application/json");
        event.putObject("data").put("requestId", id).put("kind", "document").put("namespace", "sandbox")
                .put("base", "business-kb").put("documentId", "meeting:rec-p2-c2-design").put("label", "internal");
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/data-protection").detailType(
                        "io.stintflow.knowledge.erasure-requested.v1").detail(detail).build()));
    }

    private URI storedRef(String recordId) {
        var item = floci.ddb.getItem(r -> r.tableName("records-catalog").consistentRead(true).key(Map.of("recordId",
                AttributeValue.fromS(recordId), "version", AttributeValue.fromN("1")))).item();
        return URI.create(item.get("storedRef").s());
    }

    private boolean exists(URI ref) {
        try {
            floci.s3.headObject(r -> r.bucket(ref.getHost()).key(ref.getPath().substring(1)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private JsonNode bus(String port, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }
}
