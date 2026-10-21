package io.stintflow.sandbox.records;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ErrorInfo;
import io.stintflow.wire.ClaimCheck;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.5, 8c: the task behind {@code register-document}. Input:
 * {@code {documentId, documentRef, kind, ownerRole, sensitivity, projectId?, cycle?}}. It reads the document by
 * pointer, hashes it, keeps a copy under {@code <recordId>/v<n>} and a catalog entry, and answers ids, version, hash
 * and the record's pointer — never the document. The same content again is the same version (a redelivered task, or a
 * retry, registers nothing twice); new content is the next version, which {@code supersedes} the one before it (S.7: a
 * content equal to an older version, after a newer one, is a new version too). The chain and the cause come from the
 * task, never from the input; a document of a meeting names that meeting. The workflow that called it publishes
 * {@code document-registered} from this answer (8b).
 */
public final class RegisterDocumentHandler implements TaskHandler {

    public static final String ROUTE = "register-document";
    static final Set<String> KINDS = Set.of("design", "questions", "sdd-batch", "sdd", "report", "transcript-redacted",
            "email", "page", "patch", "dlq-decision");
    /** The kinds that come out of a meeting: they must name the meeting they came from (SDD S.7, 8a). */
    static final Set<String> MEETING_KINDS = Set.of("design", "questions", "transcript-redacted");
    /** Buckets of a restricted class: nothing in the register may point at them (SDD S.7, RF8). */
    static final Set<String> RESTRICTED_BUCKETS = Set.of("sandbox-meetings-raw");
    static final String DEFAULT_RETENTION = "records";
    static final Set<String> ROLES = Set.of("business-rep", "tech-rep", "technician");
    static final Set<String> LEVELS = Set.of("internal", "confidential", "restricted");

    /** The register's catalog: one entry per (record, version). */
    public interface Catalog {
        record Entry(String recordId, int version, String sha256, String kind, String ownerRole, String sensitivity,
                String storedRef, String projectId, String cycle, String sourceMeetingId, String chainId,
                String causationId, Integer supersedes, String retentionClass) {
        }

        Optional<Entry> latest(String recordId);

        Optional<Entry> get(String recordId, int version);

        /** @return false if this version already exists (a concurrent registration won) */
        boolean putIfAbsent(Entry entry);
    }

    private final Catalog catalog;
    private final BlobStore documents;
    private final BlobStore copies;

    /**
     * @param documents reads the documents by pointer (any bucket)
     * @param copies    where the register keeps its copies
     */
    public RegisterDocumentHandler(Catalog catalog, BlobStore documents, BlobStore copies) {
        this.catalog = Objects.requireNonNull(catalog);
        this.documents = Objects.requireNonNull(documents);
        this.copies = Objects.requireNonNull(copies);
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        JsonNode in = ctx.input();
        String documentId = in.path("documentId").asText();
        String kind = in.path("kind").asText();
        String owner = in.path("ownerRole").asText();
        String level = in.path("sensitivity").asText();
        if (!documentId.matches("[A-Za-z0-9._:-]{1,100}") || !KINDS.contains(kind) || !ROLES.contains(owner)
                || !LEVELS.contains(level)) {
            return CompletableFuture.failedFuture(invalid(
                    "register-document needs a documentId, a known kind, owner role and sensitivity"));
        }
        URI ref;
        try {
            JsonNode pointer = in.path("documentRef");
            ref = URI.create(ClaimCheck.isPointer(pointer) ? pointer.path(ClaimCheck.POINTER_FIELD).asText()
                    : pointer.asText());
            if (ref.getScheme() == null) {
                throw new IllegalArgumentException("no scheme");
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(invalid("documentRef is not a pointer"));
        }
        if (ref.getHost() != null && RESTRICTED_BUCKETS.contains(ref.getHost())) {
            return CompletableFuture.failedFuture(invalid("documentRef points at a restricted bucket"));
        }
        String meeting = in.path("sourceMeetingId").asText(null);
        if (MEETING_KINDS.contains(kind) && (meeting == null || !meeting.matches("[A-Za-z0-9._:-]{1,100}"))) {
            return CompletableFuture.failedFuture(invalid("a " + kind + " needs the sourceMeetingId it came from"));
        }
        String retention = in.path("retentionClass").asText(DEFAULT_RETENTION);
        String chainId = ctx.lineage() != null && ctx.lineage().chainId() != null ? ctx.lineage().chainId()
                : ctx.chainId();
        String causationId = ctx.lineage() == null ? null : ctx.lineage().causationId();
        String recordId = "rec-" + documentId;
        return documents.get(ref).thenCompose(bytes -> {
            String sha = Corpus.sha256(bytes);
            Optional<Catalog.Entry> latest = catalog.latest(recordId);
            if (latest.isPresent() && latest.get().sha256().equals(sha)) {
                return CompletableFuture.completedFuture(answer(latest.get()));
            }
            int version = latest.map(e -> e.version() + 1).orElse(1);
            return copies.put(bytes, recordId + "/v" + version).thenApply(stored -> {
                Catalog.Entry entry = new Catalog.Entry(recordId, version, sha, kind, owner, level, stored.toString(),
                        in.path("projectId").asText(null), in.path("cycle").asText(null), meeting, chainId,
                        causationId, latest.map(Catalog.Entry::version).orElse(null), retention);
                if (!catalog.putIfAbsent(entry)) {
                    Catalog.Entry won = catalog.latest(recordId).orElseThrow();
                    if (!won.sha256().equals(sha)) {
                        throw new TaskFailure(ErrorInfo.TYPE_COMMUNICATION, 409,
                                "concurrent registration of " + recordId + "; retry", null);
                    }
                    return answer(won);
                }
                return answer(entry);
            });
        });
    }

    private static TaskFailure invalid(String detail) {
        return new TaskFailure(ErrorInfo.TYPE_VALIDATION, 400, detail, null);
    }

    static JsonNode answer(Catalog.Entry e) {
        ObjectNode out = Json.obj().put("recordId", e.recordId()).put("version", e.version()).put("sha256", e.sha256())
                .put("kind", e.kind()).put("ownerRole", e.ownerRole()).put("sensitivity", e.sensitivity())
                .put("recordRef", "record://" + e.recordId() + "/" + e.version());
        if (e.supersedes() != null) {
            out.put("supersedes", e.supersedes());
        }
        return out;
    }
}
