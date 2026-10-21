package io.stintflow.sandbox.engineering;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.5, 8e/8h: the CI's writers of the Engineering domain, from the corpus's key (the generator is S.9).
 * <ul>
 *   <li>{@code write-sdds}: one markdown per SDD the key lists ({@code sdd/<sddId>.md}) and the batch's manifest
 *       ({@code sdd/<project>-c<cycle>-batch.json}: ids, titles, pointers, and the record ids by the register's rule
 *       {@code rec-<documentId>}). Output {@code {batchId, batchRef, sdds: [{sddId, sddRef}]}}.</li>
 *   <li>{@code write-questions}: the questions as markdown. Output {@code {documentId, questionsRef, questionCount}}.</li>
 * </ul>
 * Outputs hold ids and pointers only.
 */
public final class EngineeringWriters {

    private EngineeringWriters() {
    }

    public static TaskHandler sdds(Corpus corpus, BlobStore documents) {
        return ctx -> {
            JsonNode in = ctx.input();
            Corpus.Meeting meeting = corpus.meeting(in.path("meetingId").asText());
            String project = in.path("projectId").asText();
            String cycle = in.path("cycle").asText();
            String batchId = project + "-c" + cycle + "-batch";
            List<Corpus.Sdd> sdds = meeting.expected().sdds() == null ? List.of() : meeting.expected().sdds();
            ObjectNode manifest = Json.obj().put("batchId", batchId).put("projectId", project).put("cycle", cycle)
                    .put("designRecordId", "rec-" + project + "-c" + cycle + "-design")
                    .put("techRecordId", "rec-" + batchId);
            ArrayNode listed = manifest.putArray("sdds");
            ArrayNode out = Json.MAPPER.createArrayNode();
            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (Corpus.Sdd sdd : sdds) {
                StringBuilder md = new StringBuilder("# SDD ").append(sdd.id()).append(": ").append(sdd.title())
                        .append("\n\nFrom meeting ").append(meeting.id()).append(".\n\n## Must cover\n");
                sdd.covers().forEach(c -> md.append("- ").append(c).append('\n'));
                chain = chain.thenCompose(v -> documents.put(md.toString().getBytes(StandardCharsets.UTF_8),
                        "sdd/" + sdd.id() + ".md").thenAccept(uri -> {
                            listed.addObject().put("sddId", sdd.id()).put("title", sdd.title())
                                    .put("sddRef", uri.toString()).put("recordId", "rec-" + sdd.id());
                            out.addObject().put("sddId", sdd.id()).put("sddRef", uri.toString());
                        }));
            }
            return chain.thenCompose(v -> documents.put(Json.bytes(manifest), "sdd/" + batchId + ".json"))
                    .thenApply(uri -> {
                        ObjectNode result = Json.obj().put("batchId", batchId).put("batchRef", uri.toString());
                        result.set("sdds", out);
                        return (JsonNode) result;
                    });
        };
    }

    public static TaskHandler questions(Corpus corpus, BlobStore documents) {
        return ctx -> {
            JsonNode in = ctx.input();
            Corpus.Meeting meeting = corpus.meeting(in.path("meetingId").asText());
            String documentId = in.path("projectId").asText() + "-c" + in.path("cycle").asText() + "-questions";
            List<String> questions = meeting.expected().questions() == null || meeting.expected().questions().isEmpty()
                    ? List.of("What does Business need first?") : meeting.expected().questions();
            StringBuilder md = new StringBuilder("# Questions from engineering (").append(documentId).append(")\n\n");
            for (int i = 0; i < questions.size(); i++) {
                md.append(i + 1).append(". ").append(questions.get(i)).append('\n');
            }
            return documents.put(md.toString().getBytes(StandardCharsets.UTF_8), "questions/" + documentId + ".md")
                    .thenApply(uri -> (JsonNode) Json.obj().put("documentId", documentId)
                            .put("questionsRef", uri.toString()).put("questionCount", questions.size()));
        };
    }
}
