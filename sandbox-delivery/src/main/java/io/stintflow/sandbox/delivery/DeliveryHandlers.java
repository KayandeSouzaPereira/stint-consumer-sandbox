package io.stintflow.sandbox.delivery;

import java.net.URI;
import java.util.List;
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
        return register(registry, corpus, documents, state, new ParkedSdds.InMemory());
    }

    public static TaskHandlerRegistry register(TaskHandlerRegistry registry, Corpus corpus, BlobStore documents,
            StateStore state, ParkedSdds parked) {
        return registry.register("load-batch", loadBatch(documents)).register("implement", implement(corpus, documents))
                .register("validate", validate(documents)).register("check-sdds", checkSdds(state))
                .register("park-sdd", park(parked)).register("unpark-sdd", unpark(parked))
                .register("summarize-dlq", summarizeDlq(parked, documents));
    }

    /** {@code park-sdd}: an SDD went to the DLQ ({@code {projectId, cycle, sddId, attempts}}). */
    static TaskHandler park(ParkedSdds parked) {
        return ctx -> {
            JsonNode in = ctx.input();
            parked.park(new ParkedSdds.Parked(in.path("projectId").asText(), in.path("cycle").asText(),
                    in.path("sddId").asText(), in.path("attempts").asInt()));
            return CompletableFuture.completedFuture(Json.obj().put("parked", true));
        };
    }

    /** {@code unpark-sdd}: the technician decided ({@code {projectId, cycle, sddId}}). */
    static TaskHandler unpark(ParkedSdds parked) {
        return ctx -> {
            JsonNode in = ctx.input();
            parked.unpark(in.path("projectId").asText(), in.path("cycle").asText(), in.path("sddId").asText());
            return CompletableFuture.completedFuture(Json.obj().put("parked", false));
        };
    }

    /**
     * {@code summarize-dlq} (SDD S.10, RF5): the SDDs waiting for the technician, as a text of ids and counts written
     * to {@code dlq/summary-<occurrence>.txt}. Input {@code {occurredAt}}; output {@code {count, summaryRef?}}: nothing
     * is written when nothing waits, so no e-mail follows.
     */
    static TaskHandler summarizeDlq(ParkedSdds parked, BlobStore documents) {
        return ctx -> {
            List<ParkedSdds.Parked> waiting = parked.all();
            if (waiting.isEmpty()) {
                return CompletableFuture.completedFuture(Json.obj().put("count", 0));
            }
            StringBuilder text = new StringBuilder("SDDs waiting for the technician: " + waiting.size() + "\n");
            waiting.forEach(p -> text.append("- ").append(p.projectId()).append(" c").append(p.cycle()).append(' ')
                    .append(p.sddId()).append(" (").append(p.attempts()).append(" attempts)\n"));
            String when = ctx.input().path("occurredAt").asText("now").replaceAll("[^A-Za-z0-9-]", "-");
            return documents.put(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "dlq/summary-" + when + ".txt").thenApply(uri -> (JsonNode) Json.obj().put("count", waiting.size())
                            .put("summaryRef", uri.toString()));
        };
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

    /**
     * The fake implementer: it reads the SDD and cites its requirements and criteria in the changeset (the convention
     * the validators check), so a script's SDDs validate once the target's tests pass. It ignores the feedback it is
     * given ({@code feedbackRef}, {@code failedCriteria}, {@code noteRef}): the CI's agent is deterministic.
     */
    static TaskHandler implement(Corpus corpus, BlobStore documents) {
        return ctx -> {
            String sddId = ctx.input().path("sddId").asText();
            int attempt = ctx.input().path("attempt").asInt();
            int failures = corpus.sdd(sddId).map(Corpus.Sdd::failures).orElse(0);
            CompletableFuture<String> sdd = ctx.input().hasNonNull("sddRef")
                    ? documents.get(pointer(ctx.input().get("sddRef"))).thenApply(b -> new String(b,
                            java.nio.charset.StandardCharsets.UTF_8)).toCompletableFuture()
                    : CompletableFuture.completedFuture("");
            return sdd.thenCompose(text -> documents.put(Json.bytes(changeset(sddId, attempt <= failures,
                    Validators.criteria(text), Validators.requirements(text))),
                    "changesets/" + sddId + "/" + attempt + ".json"))
                    .thenApply(uri -> (JsonNode) Json.obj().put("changesetRef", uri.toString()));
        };
    }

    static ObjectNode changeset(String sddId, boolean failing) {
        return changeset(sddId, failing, List.of(), List.of());
    }

    /** A real module and its test for the target project; the failing one asserts a wrong count. */
    static ObjectNode changeset(String sddId, boolean failing, List<String> criteria, List<String> requirements) {
        String module = "sdd_" + sddId.replaceAll("[^A-Za-z0-9]", "_");
        String cites = criteria.stream().map(c -> "# " + c + "\n").collect(java.util.stream.Collectors.joining());
        ObjectNode changeset = Json.obj();
        ArrayNode files = changeset.putArray("files");
        files.addObject().put("path", "catalog/" + module + ".py").put("content",
                "\"\"\"" + sddId + (requirements.isEmpty() ? "" : ": " + String.join(" ", requirements)) + ".\"\"\"\n\n\ndef by_author(catalog, author):\n"
                        + "    wanted = author.casefold()\n"
                        + "    return [b for b in catalog.all() if b.author.casefold() == wanted]\n");
        files.addObject().put("path", "tests/test_" + module + ".py").put("content",
                (failing ? FAILING + "\n" : "") + cites + "import unittest\n\nfrom catalog.books import Book, Catalog\n"
                        + "from catalog." + module + " import by_author\n\n\n"
                        + "class " + Character.toUpperCase(module.charAt(0)) + module.substring(1).replace("_", "")
                        + "Test(unittest.TestCase):\n    def test_by_author(self):\n"
                        + "        c = Catalog()\n        c.add(Book('9780000000002', 'Beta', 'Author One', 2001))\n"
                        + "        self.assertEqual(" + (failing ? "2" : "1")
                        + ", len(by_author(c, 'author one')))\n");
        return changeset;
    }

    /**
     * {@code validate} (SDD S.10, RF2): the three checks of {@link Validators} over the SDD, the changeset, the technical
     * batch and the design. Input {@code {sddId, attempt, sddRef, changesetRef, techRef?, designRef?, tests}}; output
     * {@code {validated, reportRef, failedCriteria}}, the report (ids and verdicts) written to
     * {@code reports/<sddId>/<attempt>.json}.
     */
    static TaskHandler validate(BlobStore documents) {
        return ctx -> {
            JsonNode in = ctx.input();
            JsonNode tests = in.path("tests");
            String sddId = in.path("sddId").asText();
            int attempt = in.path("attempt").asInt();
            CompletableFuture<String> sdd = text(documents, in.get("sddRef"));
            CompletableFuture<String> tech = text(documents, in.get("techRef"));
            CompletableFuture<String> design = text(documents, in.get("designRef"));
            CompletableFuture<JsonNode> changeset = documents.get(pointer(in.get("changesetRef")))
                    .thenApply(Json::read).toCompletableFuture();
            return CompletableFuture.allOf(sdd, tech, design, changeset).thenCompose(v -> {
                Validators.Verdict verdict = Validators.run(sddId, sdd.join(), tech.join(), design.join(),
                        changeset.join(), tests.path("passed").asBoolean());
                return documents.put(Json.bytes(verdict.toJson(sddId, attempt, tests.path("reportRef").asText(null))),
                        "reports/" + sddId + "/" + attempt + ".json").thenApply(uri -> {
                            ObjectNode out = Json.obj().put("validated", verdict.validated()).put("reportRef",
                                    uri.toString());
                            verdict.failedCriteria().forEach(out.putArray("failedCriteria")::add);
                            return (JsonNode) out;
                        });
            });
        };
    }

    /** A document's text by pointer, or null when there is no pointer. */
    private static CompletableFuture<String> text(BlobStore documents, JsonNode ref) {
        if (ref == null || ref.isNull() || ref.isMissingNode() || ref.asText("").isBlank() && !ClaimCheck.isPointer(ref)) {
            return CompletableFuture.completedFuture(null);
        }
        return documents.get(pointer(ref)).thenApply(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8))
                .toCompletableFuture();
    }

    private static URI pointer(JsonNode node) {
        return URI.create(ClaimCheck.isPointer(node) ? node.path(ClaimCheck.POINTER_FIELD).asText() : node.asText());
    }
}
