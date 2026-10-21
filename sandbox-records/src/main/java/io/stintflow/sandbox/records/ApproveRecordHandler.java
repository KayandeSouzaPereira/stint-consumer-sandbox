package io.stintflow.sandbox.records;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.spi.ErrorInfo;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.7, RF3 and 8c: the task behind {@code approve-record}. Input
 * {@code {recordId, version, approver, decision, note?, reviewId}}. The decision is appended to the version's
 * approvals, keyed by {@code reviewId}: the same review again changes nothing (the first decision stands, and a
 * different decision under the same id is a conflict). The document's version is never touched. The note is the
 * caller's, already through the privacy filter; the answer carries no note.
 */
public final class ApproveRecordHandler implements TaskHandler {

    public static final String ROUTE = "approve-record";
    static final Set<String> DECISIONS = Set.of("approved", "rejected");

    /** The approvals of each (record, version), in a table of their own so the version stays immutable. */
    public interface Approvals {
        record Approval(String recordId, int version, String reviewId, String approver, String decision, String note) {
        }

        /** @return the approval already stored under this review id, or empty if this call stored it */
        Optional<Approval> putIfAbsent(Approval approval);

        List<Approval> of(String recordId, int version);
    }

    private final RegisterDocumentHandler.Catalog catalog;
    private final Approvals approvals;

    public ApproveRecordHandler(RegisterDocumentHandler.Catalog catalog, Approvals approvals) {
        this.catalog = Objects.requireNonNull(catalog);
        this.approvals = Objects.requireNonNull(approvals);
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        JsonNode in = ctx.input();
        String recordId = in.path("recordId").asText();
        String reviewId = in.path("reviewId").asText();
        String approver = in.path("approver").asText();
        String decision = in.path("decision").asText();
        if (!recordId.matches("[A-Za-z0-9._:-]{1,128}") || !in.path("version").canConvertToInt()
                || in.path("version").asInt() < 1 || !reviewId.matches("[A-Za-z0-9._:-]{1,128}")
                || !RegisterDocumentHandler.ROLES.contains(approver) || !DECISIONS.contains(decision)) {
            return failed(400, "approve-record needs a recordId, a version, a reviewId, a known approver and a decision");
        }
        int version = in.path("version").asInt();
        if (catalog.get(recordId, version).isEmpty()) {
            return failed(404, "no such record version: " + recordId + "/" + version);
        }
        var approval = new Approvals.Approval(recordId, version, reviewId, approver, decision,
                in.path("note").asText(null));
        Optional<Approvals.Approval> existing = approvals.putIfAbsent(approval);
        if (existing.isPresent() && !existing.get().decision().equals(decision)) {
            return failed(409, "review " + reviewId + " already decided " + existing.get().decision());
        }
        ObjectNode out = Json.obj().put("recordId", recordId).put("version", version).put("reviewId", reviewId)
                .put("decision", decision);
        ArrayNode all = out.putArray("approvals");
        approvals.of(recordId, version).forEach(a -> all.add(
                Json.obj().put("reviewId", a.reviewId()).put("approver", a.approver()).put("decision", a.decision())));
        return CompletableFuture.completedFuture(out);
    }

    private static CompletionStage<JsonNode> failed(int status, String detail) {
        return CompletableFuture.failedFuture(new TaskFailure(ErrorInfo.TYPE_VALIDATION, status, detail, null));
    }
}
