package io.stintflow.sandbox.engineering;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * SDD S.5, 8e/8h and S.9: the CI's writers of the Engineering domain, from the corpus's key.
 * <ul>
 *   <li>{@code write-sdds}: one markdown per SDD the key lists ({@code sdd/<sddId>.md}), in the process's format
 *       ({@link SddFormat}), and the batch's manifest ({@code sdd/<project>-c<cycle>-batch.json}: ids, titles, pointers,
 *       and the record ids by the register's rule {@code rec-<documentId>}). Output {@code {batchId, batchRef, sdds:
 *       [{sddId, sddRef}]}}. Asked again with {@code only} (the SDDs the technical representative rejected), it writes
 *       those again — the previous text with a numbered revision section — and leaves the others alone (S.9, RF3).</li>
 *   <li>{@code finalize-batch}: the manifest of the SDDs that stayed approved, when some were given up.</li>
 *   <li>{@code write-questions}: the questions as markdown. Output {@code {documentId, questionsRef, questionCount}}.</li>
 * </ul>
 * Outputs hold ids and pointers only. The revision does not read the approver's note (it arrives by pointer and is a
 * person's text): S.9, finding S9-3.
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
            Map<String, String> refs = new LinkedHashMap<>();
            in.path("sdds").forEach(s -> refs.put(s.path("sddId").asText(), s.path("sddRef").asText()));
            Set<String> only = new java.util.HashSet<>();
            in.path("only").forEach(o -> only.add(o.asText()));
            int round = in.path("round").asInt(1);

            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (Corpus.Sdd sdd : sdds) {
                boolean rewrite = !only.isEmpty() && only.contains(sdd.id()) && refs.containsKey(sdd.id());
                if (!only.isEmpty() && !rewrite) {
                    continue;   // a rewrite touches only what was rejected
                }
                chain = chain.thenCompose(v -> (rewrite ? revised(documents, refs.get(sdd.id()), round)
                        : CompletableFuture.completedFuture(first(meeting, sdd))).thenCompose(md -> documents
                                .put(md.getBytes(StandardCharsets.UTF_8), "sdd/" + sdd.id() + ".md"))
                        .thenAccept(uri -> refs.put(sdd.id(), uri.toString())));
            }
            return chain.thenCompose(v -> {
                ObjectNode result = Json.obj().put("batchId", batchId);
                ArrayNode out = result.putArray("sdds");
                Map<String, String> listed = new LinkedHashMap<>();
                for (Corpus.Sdd sdd : sdds) {
                    if (refs.containsKey(sdd.id())) {
                        listed.put(sdd.id(), refs.get(sdd.id()));
                        out.addObject().put("sddId", sdd.id()).put("sddRef", refs.get(sdd.id()));
                    }
                }
                return documents.put(Json.bytes(manifest(project, cycle, batchId, sdds, listed)),
                        "sdd/" + batchId + ".json").thenApply(uri -> {
                            result.put("batchRef", uri.toString());
                            return (JsonNode) result;
                        });
            });
        };
    }

    /** The manifest of the SDDs that stayed approved: the others were given up after their rounds. */
    public static TaskHandler finalizeBatch(Corpus corpus, BlobStore documents) {
        return ctx -> {
            JsonNode in = ctx.input();
            Corpus.Meeting meeting = corpus.meeting(in.path("meetingId").asText());
            String project = in.path("projectId").asText();
            String cycle = in.path("cycle").asText();
            String batchId = project + "-c" + cycle + "-batch";
            Set<String> keep = new java.util.HashSet<>();
            in.path("keep").forEach(k -> keep.add(k.asText()));
            Map<String, String> listed = new LinkedHashMap<>();
            ArrayNode out = Json.MAPPER.createArrayNode();
            in.path("sdds").forEach(s -> {
                if (keep.contains(s.path("sddId").asText())) {
                    listed.put(s.path("sddId").asText(), s.path("sddRef").asText());
                    out.addObject().put("sddId", s.path("sddId").asText()).put("sddRef", s.path("sddRef").asText());
                }
            });
            List<Corpus.Sdd> sdds = meeting.expected().sdds() == null ? List.of() : meeting.expected().sdds();
            return documents.put(Json.bytes(manifest(project, cycle, batchId, sdds, listed)),
                    "sdd/" + batchId + ".json").thenApply(uri -> {
                        ObjectNode result = Json.obj().put("batchId", batchId).put("batchRef", uri.toString());
                        result.set("sdds", out);
                        return (JsonNode) result;
                    });
        };
    }

    private static ObjectNode manifest(String project, String cycle, String batchId, List<Corpus.Sdd> sdds,
            Map<String, String> listedRefs) {
        ObjectNode manifest = Json.obj().put("batchId", batchId).put("projectId", project).put("cycle", cycle)
                .put("designRecordId", "rec-" + project + "-c" + cycle + "-design")
                .put("techRecordId", "rec-" + batchId);
        ArrayNode listed = manifest.putArray("sdds");
        for (Corpus.Sdd sdd : sdds) {
            if (listedRefs.containsKey(sdd.id())) {
                listed.addObject().put("sddId", sdd.id()).put("title", sdd.title())
                        .put("sddRef", listedRefs.get(sdd.id())).put("recordId", "rec-" + sdd.id());
            }
        }
        return manifest;
    }

    /** The SDD as the process writes it: what the meeting said, a requirement and a criterion per thing it must cover. */
    static String first(Corpus.Meeting meeting, Corpus.Sdd sdd) {
        List<String> covers = sdd.covers() == null ? List.of() : sdd.covers();
        List<String> requirements = new ArrayList<>();
        List<String> criteria = new ArrayList<>();
        for (String cover : covers) {
            requirements.add("The work covers: " + cover + ".");
            criteria.add("Verified: " + cover + ".");
        }
        if (covers.isEmpty()) {
            requirements.add("The work is done as the meeting decided.");
            criteria.add("Verified against the meeting's decision.");
        }
        return SddFormat.write(sdd.id(), sdd.title(),
                "From meeting " + meeting.id() + ". " + sdd.title() + ".", requirements, criteria);
    }

    /** The previous text with a numbered revision section; the note is not read (it is a person's text). */
    private static CompletionStage<String> revised(BlobStore documents, String ref, int round) {
        return documents.get(URI.create(ref)).thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8)
                + "\n## Revisão " + (round - 1) + "\n- Revised after the technical representative's note.\n");
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
