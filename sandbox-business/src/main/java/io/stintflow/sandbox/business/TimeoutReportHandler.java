package io.stintflow.sandbox.business;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.spi.BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.8, RF3: the task behind {@code write-timeout-report}. A design nobody approved, even after the escalation, is
 * documented: this writes the report — ids and counts only, never a word of the meeting or the design — to
 * {@code sandbox-documents} as {@code report/<documentId>-approval-timeout.md}, and answers {@code {documentId,
 * reportRef}}. The workflow registers it (kind {@code report}) and tells the leads.
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
        String documentId = in.path("documentId").asText() + "-approval-timeout";
        String markdown = "# Approval timeout " + documentId + "\n- project: " + in.path("projectId").asText()
                + "\n- cycle: " + in.path("cycle").asText() + "\n- design: " + in.path("documentId").asText()
                + " version " + in.path("version").asInt() + " after " + in.path("round").asInt()
                + " round(s)\n- the business representative and the business lead did not answer in time;"
                + " nothing was published\n";
        return documents.put(markdown.getBytes(StandardCharsets.UTF_8), "report/" + documentId + ".md")
                .thenApply(uri -> Json.obj().put("documentId", documentId).put("reportRef", uri.toString()));
    }
}
