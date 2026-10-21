package io.stintflow.sandbox.business;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.spi.BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.langchain4j.LangChain4jWorker;

/**
 * SDD S.5, 8h: the task behind {@code write-design}. Input {@code {meetingId, projectId, cycle, text}} (the redacted
 * transcript). It writes the design document — markdown — to {@code sandbox-documents} as
 * {@code design/<projectId>-c<cycle>-design.md} and answers {@code {documentId, designRef}}: never the document.
 * The fake (CI) builds it from the redacted text; the manual run asks the local model ({@link DesignWriter}).
 */
public final class DesignHandler implements TaskHandler {

    public static final String ROUTE = "write-design";

    private final TaskHandler model;
    private final BlobStore documents;

    private DesignHandler(TaskHandler model, BlobStore documents) {
        this.model = model;
        this.documents = Objects.requireNonNull(documents);
    }

    /** The CI's writer: the redacted lines, as the design's notes. */
    public static DesignHandler fake(BlobStore documents) {
        return new DesignHandler(ctx -> {
            List<String> lines = ctx.input().path("text").asText().lines().map(String::strip)
                    .filter(l -> !l.isEmpty()).toList();
            return CompletableFuture.completedFuture(Json.MAPPER.valueToTree(new DesignWriter.Design(
                    "Design from meeting " + ctx.input().path("meetingId").asText() + ".", List.of(), lines,
                    List.of())));
        }, documents);
    }

    /** The manual run's writer, through Stint's LangChain4j worker. */
    public static DesignHandler llm(LangChain4jWorker worker, BlobStore documents) {
        return new DesignHandler(worker.handler(DesignWriter.class, (ai, task) -> ai.write(String.format(
                DesignWriter.PROMPT, task.input().path("text").asText()))), documents);
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        String documentId = ctx.input().path("projectId").asText() + "-c" + ctx.input().path("cycle").asText()
                + "-design";
        return model.execute(ctx).thenCompose(structured -> {
            DesignWriter.Design design = Json.MAPPER.convertValue(structured, DesignWriter.Design.class);
            byte[] markdown = design.markdown("Design " + documentId).getBytes(StandardCharsets.UTF_8);
            return documents.put(markdown, "design/" + documentId + ".md").thenApply(uri -> {
                ObjectNode out = Json.obj().put("documentId", documentId).put("designRef", uri.toString());
                return (JsonNode) out;
            });
        });
    }
}
