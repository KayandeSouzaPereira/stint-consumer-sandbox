package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * SDD S.13, RF5 (CA5): after project p1's cycle, the design is erased from the Business base and the result is checked
 * from outside, on floci: what the base kept in S3 for that document is gone, the base does not return it, and the
 * register's copy (the Records domain's) is still there — an erasure of a base never reaches another domain's store
 * (finding S13-1). Announcing the same design again after the erasure is refused by the tombstone.
 */
class KnowledgeErasureOutsideIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String KB = "sandbox/business-kb";
    static final String DOCUMENT = "meeting:rec-p1-c1-design";

    @Test
    void erasing_a_document_leaves_nothing_of_it_in_the_base_and_nothing_gone_from_the_register() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR)));
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        awaitClosed("p1", "1");
        spy.until("the design indexed", () -> indexed() >= 1, Duration.ofMinutes(2));
        spy.settle(Duration.ofSeconds(5));

        URI register = storedRef("rec-p1-c1-design");
        assertThat(exists(register)).as("the register holds its copy of the design").isTrue();
        Set<String> before = keys("sandbox-business-blobs");
        long nodesBefore = json("/graph/" + KB + "/view").path("nodes").size();
        assertThat(nodesBefore).isPositive();

        request("ex-1");
        spy.until("the erasure completed", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.stintflow.knowledge.erasure-completed.v1") && "ex-1".equals(subject(e))), Duration.ofMinutes(3));
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.erasure-incomplete.v1")))
                .isEmpty();

        Set<String> after = keys("sandbox-business-blobs");
        Set<String> gone = new TreeSet<>(before);
        gone.removeAll(after);
        note("business blobs: %d before, %d after, %d gone", before.size(), after.size(), gone.size());
        assertThat(gone).as("the blobs the base kept for the document (its batch) are gone").isNotEmpty();
        assertThat(json("/graph/" + KB + "/view").path("nodes").size()).as("the document's nodes are gone")
                .isLessThan((int) nodesBefore);
        assertThat(json("/graph/" + KB + "/changes?after=0&limit=500&document=" + DOCUMENT).toString())
                .as("the log keeps the erasure").contains("ERASE");
        boolean registerSurvives = exists(register);
        note("the register's copy after the erasure: %s", registerSurvives ? "still there" : "GONE");
        assertThat(registerSurvives).as("another domain's store is untouched by the erasure of a base").isTrue();

        // the tombstone: the same design announced again is not taken back
        long indexedBefore = indexed();
        String sha = floci.ddb.getItem(r -> r.tableName("records-catalog").key(Map.of("recordId",
                AttributeValue.fromS("rec-p1-c1-design"), "version", AttributeValue.fromN("1")))).item().get("sha256").s();
        new KnowledgePlant(floci).announce("rec-p1-c1-design", 1, sha, "p1", "p1:replay:after-erasure");
        spy.until("the replay answered", () -> indexed() > indexedBefore, Duration.ofMinutes(3));
        String status = spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.document-indexed.v1"))
                .reduce((a, b) -> b).map(KnowledgeErasureOutsideIT::status).orElse("");
        assertThat(status).as("the tombstone refuses the document").isEqualTo("REJECTED_ERASED");
        Scenario.evidence("S.13-CA5.txt", "S.13 CA5: the design erased from the Business base, checked on floci\n" + timeline()
                + "  business blobs gone: " + gone.size() + " of " + before.size() + "\n  the register's copy: "
                + (registerSurvives ? "still there" : "gone") + "\n  replay after the erasure: " + status + "\n");
    }

    private static String status(Spy.Event e) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path("status").asText();
        } catch (Exception ex) {
            return "";
        }
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

    private Set<String> keys(String bucket) {
        Set<String> out = new TreeSet<>();
        String token = null;
        do {
            String t = token;
            ListObjectsV2Response page = floci.s3.listObjectsV2(r -> r.bucket(bucket).continuationToken(t));
            page.contents().forEach(o -> out.add(o.key()));
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return out;
    }

    private void request(String id) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", id + "-fact")
                .put("type", "io.stintflow.knowledge.erasure-requested.v1")
                .put("source", "https://sandbox.stintflow.io/data-protection").put("datacontenttype", "application/json");
        event.putObject("data").put("requestId", id).put("kind", "document").put("namespace", "sandbox")
                .put("base", "business-kb").put("documentId", DOCUMENT).put("label", "internal");
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/data-protection").detailType(
                        "io.stintflow.knowledge.erasure-requested.v1").detail(detail).build()));
    }

    private long indexed() {
        return spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.document-indexed.v1")).count();
    }

    private JsonNode json(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8197" + path))
                .header("X-Stint-Operator", OPERATOR).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return Spy.JSON.readTree(response.body());
    }
}
