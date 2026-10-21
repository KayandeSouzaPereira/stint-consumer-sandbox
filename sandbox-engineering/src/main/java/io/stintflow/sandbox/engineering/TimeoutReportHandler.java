package io.stintflow.sandbox.engineering;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.spi.BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.9, RF4: the task behind {@code write-timeout-report}. A batch of SDDs nobody approved, even after the
 * escalation, is documented: this writes the report — ids and counts only, never a word of the meeting or an SDD — to
 * {@code sandbox-documents} as {@code report/<batchId>-approval-timeout.md} and answers {@code {documentId,
 * reportRef}}. The workflow registers it (kind {@code report}) and tells the leads. (The Business domain has its own
 * copy: no domain depends on another.)
 */
public final class TimeoutReportHandler implements TaskHandler {

    public static final String ROUTE = "write-timeout-report";

    private final BlobStore documents;

    public TimeoutReportHandler(BlobStore documents) {
        this.documents = Objects.requireNonNull(documents);
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        JsonNode in = ctx.input();
        String documentId = in.path("batchId").asText() + "-approval-timeout";
        String markdown = "# Approval timeout " + documentId + "\n- project: " + in.path("projectId").asText()
                + "\n- cycle: " + in.path("cycle").asText() + "\n- batch: " + in.path("batchId").asText() + " with "
                + in.path("count").asInt() + " SDD(s) after " + in.path("round").asInt()
                + " round(s)\n- the technical representative and the technical lead did not answer in time;"
                + " nothing was sent to delivery\n";
        return documents.put(markdown.getBytes(StandardCharsets.UTF_8), "report/" + documentId + ".md")
                .thenApply(uri -> Json.obj().put("documentId", documentId).put("reportRef", uri.toString()));
    }
}
