package io.stintflow.sandbox.delivery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.StateStore;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.10, step 1: the Delivery's routes for a test, with stand-ins that remember what they were asked — what the
 * {@code implement} of each attempt received, which documents were registered, which records were read — so a test can
 * prove what reached the next attempt. The target's tests, the register and the records' reader stand in; the
 * Delivery's own routes (the validators, the park list, the summary) are the real ones.
 */
final class DeliveryFixture {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));
    static final String NOTE = "s3://sandbox-documents/notes/technician-note.txt";

    /** The input of every {@code implement} call, in order. */
    final List<JsonNode> implemented = Collections.synchronizedList(new ArrayList<>());
    /** The input of every {@code register-document} call, in order. */
    final List<JsonNode> registered = Collections.synchronizedList(new ArrayList<>());
    final ParkedSdds.InMemory parked = new ParkedSdds.InMemory();
    /** SDD id -> the attempts that fail (1..n); an id that starts with {@code dlq-} fails five, the corpus says the rest. */
    final Map<String, Integer> failUntil = new HashMap<>();
    /** What the changeset of an SDD cites (null: all its criteria). A criterion left out is a coverage failure. */
    final Map<String, List<String>> cites = new HashMap<>();

    TaskHandlerRegistry handlers(BlobStore blobs, StateStore state) {
        TaskHandlerRegistry registry = DeliveryHandlers.register(new TaskHandlerRegistry(), CORPUS, blobs, state, parked);
        return registry.register("implement", implement(blobs)).register("run-target-tests", runTargetTests(blobs))
                .register("read-record", readRecord(blobs)).register("register-document", registerDocument(blobs));
    }

    /** The fake implementer, remembering its input and failing the attempts {@link #failUntil} says. */
    TaskHandler implement(BlobStore blobs) {
        return ctx -> {
            implemented.add(ctx.input().deepCopy());
            String sddId = ctx.input().path("sddId").asText();
            int attempt = ctx.input().path("attempt").asInt();
            int failing = failUntil.getOrDefault(sddId, sddId.startsWith("dlq-") ? 5
                    : CORPUS.sdd(sddId).map(Corpus.Sdd::failures).orElse(0));
            CompletableFuture<String> sdd = blobs.get(URI.create(ctx.input().path("sddRef").asText()))
                    .thenApply(b -> new String(b, StandardCharsets.UTF_8)).toCompletableFuture();
            return sdd.thenCompose(text -> {
                List<String> criteria = cites.containsKey(sddId) ? cites.get(sddId) : Validators.criteria(text);
                return blobs.put(Json.bytes(DeliveryHandlers.changeset(sddId, attempt <= failing, criteria,
                        Validators.requirements(text))), "changesets/" + sddId + "/" + attempt + ".json");
            }).thenApply(uri -> (JsonNode) Json.obj().put("changesetRef", uri.toString()));
        };
    }

    /** The target's tests: a changeset with the failing mark fails. */
    static TaskHandler runTargetTests(BlobStore blobs) {
        return ctx -> blobs.get(URI.create(ctx.input().path("changesetRef").asText())).thenCompose(bytes -> {
            boolean failing = Json.read(bytes).toString().contains(DeliveryHandlers.FAILING);
            String key = "runs/" + ctx.input().path("sddId").asText() + "/" + ctx.input().path("attempt").asInt()
                    + ".txt";
            return blobs.put(("Ran 5 tests\n" + (failing ? "FAILED (failures=1)" : "OK")).getBytes(), key)
                    .thenApply(uri -> (JsonNode) Json.obj().put("outcome", failing ? "failed" : "passed")
                            .put("passed", !failing).put("reportRef", uri.toString()));
        });
    }

    /** The records' reader: the batch's manifest for {@code rec-<p>-c<n>-batch}, a design text for the rest. */
    static TaskHandler readRecord(BlobStore blobs) {
        return ctx -> {
            String recordId = ctx.input().path("recordId").asText();
            if (recordId.endsWith("-batch")) {
                String key = "sdd/" + recordId.substring("rec-".length()) + ".json";
                return CompletableFuture.completedFuture(Json.obj().put("recordId", recordId).put("contentRef",
                        "s3://sandbox-test/" + key));
            }
            return blobs.put("a design".getBytes(StandardCharsets.UTF_8), "design/" + recordId + ".md")
                    .thenApply(uri -> (JsonNode) Json.obj().put("recordId", recordId).put("contentRef",
                            uri.toString()));
        };
    }

    /** The register: remembers the call, answers the record id, version 1 and the document's hash. */
    TaskHandler registerDocument(BlobStore blobs) {
        return ctx -> {
            registered.add(ctx.input().deepCopy());
            return blobs.get(URI.create(ctx.input().path("documentRef").asText())).thenApply(bytes -> (JsonNode) Json
                    .obj().put("recordId", "rec-" + ctx.input().path("documentId").asText()).put("version", 1)
                    .put("sha256", Corpus.sha256(bytes)));
        };
    }

    /** The kinds of the registered documents, by document id. */
    Map<String, String> registeredKinds() {
        Map<String, String> out = new java.util.TreeMap<>();
        synchronized (registered) {
            registered.forEach(r -> out.put(r.path("documentId").asText(), r.path("kind").asText()));
        }
        return out;
    }

    /** The {@code implement} inputs of one SDD, in order. */
    List<JsonNode> implementationsOf(String sddId) {
        synchronized (implemented) {
            return implemented.stream().filter(i -> sddId.equals(i.path("sddId").asText())).toList();
        }
    }

    /** The batch for {@link DeliveryWorkflowsTest}: SDDs with requirements and criteria the validators read. */
    static CloudEvent batch(InMemoryDomain d, String project, String cycle, List<String> sddIds) {
        ObjectNode manifest = Json.obj().put("designRecordId", "rec-" + project + "-c" + cycle + "-design")
                .put("techRecordId", "rec-" + project + "-c" + cycle + "-batch");
        ArrayNode sdds = manifest.putArray("sdds");
        for (String id : sddIds) {
            String text = "# SDD " + id + " — a title\n\n## 1. Análise\nWhat it is.\n\n## 3. Requisitos\n"
                    + "- RF1 — the first requirement\n- RF2 — the second\n\n## 5. Critérios de aceite\n"
                    + "- CA1 — proves RF1\n- CA2 — proves RF2\n";
            URI ref = d.blobs.put(text.getBytes(StandardCharsets.UTF_8), "sdd/" + id + ".md").toCompletableFuture()
                    .join();
            sdds.addObject().put("sddId", id).put("sddRef", ref.toString()).put("recordId", "rec-" + id);
        }
        URI batchRef = d.blobs.put(Json.bytes(manifest), "sdd/" + project + "-c" + cycle + "-batch.json")
                .toCompletableFuture().join();
        ObjectNode data = Json.obj().put("batchId", project + "-c" + cycle + "-batch")
                .put("batchRef", batchRef.toString());
        sddIds.forEach(data.putArray("sddIds")::add);
        data.putArray("recordIds");
        return CloudEventBuilder.v1().withId(project + ":" + cycle + ":sdd-batch-approved")
                .withSource(URI.create(PipelineEvents.SOURCE_ENGINEERING)).withType(PipelineEvents.SDD_BATCH_APPROVED)
                .withSubject(project + "-c" + cycle + "-batch").withData(Json.bytes(data))
                .withExtension(PipelineEvents.EXT_PROJECT, project).withExtension(PipelineEvents.EXT_CYCLE, cycle)
                .withExtension(StintEvents.EXT_CHAIN_ID, PipelineEvents.chainIdOf(project))
                .withExtension(StintEvents.EXT_SENSITIVITY, "confidential").build();
    }
}
