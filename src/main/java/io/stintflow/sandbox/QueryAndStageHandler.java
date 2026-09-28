package io.stintflow.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.aws.S3BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Simulates a DB query, stages the result as a real, openable CSV in S3 (via floci) — never rows
 * inline in the wire payload. Real content, unlike the placeholder text the Stint build-report
 * example uses, so the file is actually viewable end to end (attached to the email later).
 */
@ApplicationScoped
public class QueryAndStageHandler implements TaskHandler {

    @Inject
    S3BlobStore blob;

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        String query = ctx.input().get("query").asText();
        StringBuilder csv = new StringBuilder("row_id,description\n");
        int rowCount = 25;
        for (int i = 0; i < rowCount; i++) {
            csv.append(i).append(",\"row ").append(i).append(" for ").append(query.replace("\"", "'")).append("\"\n");
        }

        String key = ctx.instanceId() + "/extract.csv";
        return blob.put(csv.toString().getBytes(StandardCharsets.UTF_8), key).thenApply(uri -> {
            ObjectNode out = Json.obj();
            out.put("pointer", uri.toString());
            out.put("rows", rowCount);
            return out;
        });
    }
}
