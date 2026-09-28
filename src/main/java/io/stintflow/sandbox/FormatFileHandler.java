package io.stintflow.sandbox;

import java.net.URI;
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
 * Reads the staged CSV from S3 by pointer, prepends a real title row, stages the final file back
 * to S3. Only CSV is genuinely generated (real, openable content) — the requested {@code format}
 * is intentionally ignored for content generation (always CSV) rather than labelling non-CSV bytes
 * with a mismatched extension like the original placeholder version did.
 */
@ApplicationScoped
public class FormatFileHandler implements TaskHandler {

    @Inject
    S3BlobStore blob;

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        URI source = URI.create(ctx.input().get("source").asText());

        return blob.get(source).thenCompose(bytes -> {
            String extract = new String(bytes, StandardCharsets.UTF_8);
            String finalCsv = "# Stint sandbox report\n" + extract;
            return blob.put(finalCsv.getBytes(StandardCharsets.UTF_8), ctx.instanceId() + "/final.csv");
        }).thenApply(uri -> {
            ObjectNode out = Json.obj();
            out.put("file", uri.toString());
            return out;
        });
    }
}
