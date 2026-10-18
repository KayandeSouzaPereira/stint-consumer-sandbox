package io.stintflow.sandbox.delivery;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.StateStore;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.spi.BlobStore;
import io.stintflow.wire.ClaimCheck;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.5, 8a/8e/8f: the Delivery domain's own routes, as the CI runs them (the agents are S.10).
 * <ul>
 *   <li>{@code load-batch}: reads the batch's manifest (written by Engineering) and answers the SDDs' ids, pointers
 *       and record ids, and the design's and batch's record ids — so the coordinator's events stay ids and pointers.</li>
 *   <li>{@code implement}: the fake implementer. A changeset of whole files — a module and its test, real Python for
 *       the target project — into {@code changesets/<sddId>/<attempt>.json}. The corpus's {@code failAttempts} makes
 *       the first attempts fail (a test marked {@value #FAILING} that asserts the wrong count), for the retry and DLQ
 *       paths.</li>
 *   <li>{@code validate}: the fake validator: validated when the target's tests passed.</li>
 * </ul>
 */
public final class DeliveryHandlers {

    /** The marker the CI's runner reads (sandbox-target's FakeTestRunner); the Docker runner fails the test itself. */
    public static final String FAILING = "# fake: failing";

    private DeliveryHandlers() {
    }

    public static TaskHandlerRegistry register(TaskHandlerRegistry registry, Corpus corpus, BlobStore documents,
            StateStore state) {
        return registry.register("load-batch", loadBatch(documents)).register("implement", implement(corpus, documents))
                .register("validate", validate()).register("check-sdds", checkSdds(state));
    }

    /** The definition the SDD instances run (sdd-delivery.yaml): their ids come from the sdd-ready events. */
    static final WorkflowRef SDD_DELIVERY = new WorkflowRef("delivery", "sdd-delivery", "1.0.0");

    /**
     * {@code check-sdds}: which SDDs of a cycle have ended, from the Delivery's own state: the status and the outcome of
     * each SDD instance (its id is the one Stint gives the instance an sdd-ready starts, SDD 2.1). Input
     * {@code {projectId, cycle, sddIds}}; output {@code {settled: [{sddId, outcome}], pending: [sddId]}}: ids and
     * enums only. An instance that does not exist yet, or still runs, is pending; one that failed counts as cancelled.
     */
    static TaskHandler checkSdds(StateStore state) {
        return ctx -> {
            JsonNode in = ctx.input();
            String project = in.path("projectId").asText();
            String cycle = in.path("cycle").asText();
            ObjectNode out = Json.obj();
            ArrayNode settled = out.putArray("settled");
            ArrayNode pending = out.putArray("pending");
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (JsonNode sdd : in.path("sddIds")) {
                String sddId = sdd.asText();
                String instanceId = WorkflowEngine.triggeredInstanceId(SDD_DELIVERY, CloudEventBuilder.v1()
                        .withId(project + ":" + cycle + ":sdd-ready:" + sddId)
                        .withSource(URI.create("https://sandbox.stintflow.io/delivery"))
                        .withType("io.sandbox.delivery.sdd-ready.v1").build());
                chain = chain.thenCompose(v -> state.load(instanceId)).thenAccept(found -> {
                    InstanceStatus status = found.map(InstanceSnapshot::status).orElse(null);
                    if (status == InstanceStatus.COMPLETED || status == InstanceStatus.FAILED
                            || status == InstanceStatus.ABORTED) {
                        String outcome = found.get().context().path("outcome").asText("cancelled");
                        settled.addObject().put("sddId", sddId).put("outcome",
                                status == InstanceStatus.COMPLETED ? outcome : "cancelled");
                    } else {
                        pending.add(sddId);
                    }
                }).toCompletableFuture();
            }
            return chain.thenApply(v -> (JsonNode) out);
        };
    }

    static TaskHandler loadBatch(BlobStore documents) {
        return ctx -> documents.get(pointer(ctx.input().get("batchRef"))).thenApply(bytes -> {
            JsonNode manifest = Json.read(bytes);
            ObjectNode out = Json.obj().put("designRecordId", manifest.path("designRecordId").asText())
                    .put("techRecordId", manifest.path("techRecordId").asText());
            ArrayNode sdds = out.putArray("sdds");
            manifest.path("sdds").forEach(s -> sdds.addObject().put("sddId", s.path("sddId").asText())
                    .put("sddRef", s.path("sddRef").asText()).put("recordId", s.path("recordId").asText()));
            return (JsonNode) out;
        });
    }

    static TaskHandler implement(Corpus corpus, BlobStore documents) {
        return ctx -> {
            String sddId = ctx.input().path("sddId").asText();
            int attempt = ctx.input().path("attempt").asInt();
            int failures = corpus.sdd(sddId).map(Corpus.Sdd::failures).orElse(0);
            byte[] changeset = Json.bytes(changeset(sddId, attempt <= failures));
            return documents.put(changeset, "changesets/" + sddId + "/" + attempt + ".json")
                    .thenApply(uri -> (JsonNode) Json.obj().put("changesetRef", uri.toString()));
        };
    }

    /** A real module and its test for the target project; the failing one asserts a wrong count. */
    static ObjectNode changeset(String sddId, boolean failing) {
        String module = "sdd_" + sddId.replaceAll("[^A-Za-z0-9]", "_");
        ObjectNode changeset = Json.obj();
        ArrayNode files = changeset.putArray("files");
        files.addObject().put("path", "catalog/" + module + ".py").put("content",
                "\"\"\"" + sddId + ".\"\"\"\n\n\ndef by_author(catalog, author):\n"
                        + "    wanted = author.casefold()\n"
                        + "    return [b for b in catalog.all() if b.author.casefold() == wanted]\n");
        files.addObject().put("path", "tests/test_" + module + ".py").put("content",
                (failing ? FAILING + "\n" : "") + "import unittest\n\nfrom catalog.books import Book, Catalog\n"
                        + "from catalog." + module + " import by_author\n\n\n"
                        + "class " + Character.toUpperCase(module.charAt(0)) + module.substring(1).replace("_", "")
                        + "Test(unittest.TestCase):\n    def test_by_author(self):\n"
                        + "        c = Catalog()\n        c.add(Book('9780000000002', 'Beta', 'Author One', 2001))\n"
                        + "        self.assertEqual(" + (failing ? "2" : "1")
                        + ", len(by_author(c, 'author one')))\n");
        return changeset;
    }

    static TaskHandler validate() {
        return ctx -> {
            JsonNode tests = ctx.input().path("tests");
            return CompletableFuture.completedFuture(Json.obj().put("validated", tests.path("passed").asBoolean())
                    .put("reportRef", tests.path("reportRef").asText()));
        };
    }

    private static URI pointer(JsonNode node) {
        return URI.create(ClaimCheck.isPointer(node) ? node.path(ClaimCheck.POINTER_FIELD).asText() : node.asText());
    }
}
