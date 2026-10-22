package io.stintflow.sandbox.records;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.spi.ErrorInfo;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.7, RF5 and 8e: the task behind {@code read-record}. Input {@code {recordId, version?}} (the latest if none).
 * It answers the version's pointer, never the text, and only to the domain that owns the record: the owner role maps
 * to a domain (8d) and the caller's domain is the namespace of the workflow that asked. Any other domain gets a 403.
 * The limit is the route's: floci has no per-object access control, and nothing else reads the register's bucket.
 */
public final class ReadRecordHandler implements TaskHandler {

    public static final String ROUTE = "read-record";
    static final Map<String, String> DOMAIN_OF_ROLE = Map.of("business-rep", "business", "tech-rep", "engineering",
            "technician", "delivery");

    private final RegisterDocumentHandler.Catalog catalog;

    public ReadRecordHandler(RegisterDocumentHandler.Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        String recordId = ctx.input().path("recordId").asText();
        if (!recordId.matches("[A-Za-z0-9._:-]{1,128}")) {
            return failed(400, "read-record needs a recordId");
        }
        Optional<RegisterDocumentHandler.Catalog.Entry> found = ctx.input().path("version").canConvertToInt()
                ? catalog.get(recordId, ctx.input().path("version").asInt()) : catalog.latest(recordId);
        if (found.isEmpty()) {
            return failed(404, "no such record: " + recordId);
        }
        var e = found.get();
        String caller = ctx.definition().namespace();
        if (!DOMAIN_OF_ROLE.get(e.ownerRole()).equals(caller) && (e.readers() == null || !e.readers().contains(caller))) {
            return failed(403, "the record belongs to another domain");
        }
        return CompletableFuture.completedFuture(Json.obj().put("recordId", e.recordId()).put("version", e.version())
                .put("sha256", e.sha256()).put("kind", e.kind()).put("sensitivity", e.sensitivity())
                .put("contentRef", e.storedRef()));
    }

    private static CompletionStage<JsonNode> failed(int status, String detail) {
        return CompletableFuture.failedFuture(new TaskFailure(ErrorInfo.TYPE_VALIDATION, status, detail, null));
    }
}
