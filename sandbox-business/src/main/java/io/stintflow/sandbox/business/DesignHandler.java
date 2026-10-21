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
        int round = ctx.input().path("round").asInt(1);
        if (round > 1 && ctx.input().hasNonNull("previousRef")) {
            return revise(ctx, documentId, round);
        }
        return model.execute(ctx).thenCompose(structured -> {
            DesignWriter.Design design = Json.MAPPER.convertValue(structured, DesignWriter.Design.class);
            byte[] markdown = design.markdown("Design " + documentId).getBytes(StandardCharsets.UTF_8);
            return documents.put(markdown, "design/" + documentId + ".md").thenApply(uri -> {
                ObjectNode out = Json.obj().put("documentId", documentId).put("designRef", uri.toString());
                return (JsonNode) out;
            });
        });
    }

    /**
     * SDD S.8, RF4: a rejected design is written again as the next version of the same document — the previous text
     * with a numbered revision section. The approver's note arrives by pointer ({@code noteRef}) and is not read here
     * (it is a person's text: reading it is for a writer that has passed the privacy filter over it, a finding of S.8).
     */
    private CompletionStage<JsonNode> revise(TaskContext ctx, String documentId, int round) {
        java.net.URI previous = java.net.URI.create(ctx.input().path("previousRef").asText());
        boolean noted = ctx.input().hasNonNull("noteRef");
        return documents.get(previous).thenCompose(bytes -> {
            String revised = new String(bytes, StandardCharsets.UTF_8) + "\n## Revision " + (round - 1) + "\n- "
                    + (noted ? "Revised after the approver's note." : "Revised after the rejection.") + "\n";
            return documents.put(revised.getBytes(StandardCharsets.UTF_8), "design/" + documentId + ".md")
                    .thenApply(uri -> (JsonNode) Json.obj().put("documentId", documentId).put("designRef",
                            uri.toString()));
        });
    }
}
