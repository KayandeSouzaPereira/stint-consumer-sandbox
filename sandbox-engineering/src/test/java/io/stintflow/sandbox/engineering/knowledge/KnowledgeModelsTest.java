package io.stintflow.sandbox.engineering.knowledge;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.stintflow.inmemory.FilesystemBlobStore;
import io.stintflow.inmemory.InMemoryAuditLog;
import io.stintflow.knowledge.KnowledgeScope;
import io.stintflow.knowledge.extraction.Glossary;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.Sensitivity;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.13, RF9 and CA9: the manual measurement of the extraction with a real model (llama.cpp, Qwen3-4B, on
 * 127.0.0.1:8080), over the SDDs the technical meetings of the synthetic corpus ask for (title and what each covers). What it
 * reports is not an acceptance criterion: of the glossary terms each text names, how many the model's batch holds (and how
 * many it adds that the text does not name), how many batches the schema refused, the time of each, and whether the base
 * finds the document for a question about it. Run with {@code -Dsandbox.knowledge.models=true} after
 * {@code bash infra/local-stack.sh up}; the output is {@code target/S.13-models.txt}.
 */
class KnowledgeModelsTest {

    static final KnowledgeScope SCOPE = KnowledgeScope.named("sandbox", "engineering-kb");
    static final WorkflowRef WORKFLOW = new WorkflowRef("engineering", "knowledge-ingest", "1.0.0");
    static final List<String[]> QUESTIONS = java.util.Collections.singletonList(
            new String[] {"p2-c2-technical", "borrow with a limit of three", "limit"});

    @TempDir
    Path dir;

    @Test
    void the_extraction_of_a_real_model_over_the_business_meetings() throws Exception {
        assumeTrue(Boolean.getBoolean("sandbox.knowledge.models"), "manual run: -Dsandbox.knowledge.models=true");
        Corpus corpus = Corpus.load(Path.of("..", "corpus").toAbsolutePath().normalize());
        OpenAiChatModel llm = OpenAiChatModel.builder().baseUrl(System.getProperty("llm.base-uri",
                "http://127.0.0.1:8080") + "/v1").apiKey("local").modelName("qwen3-4b-instruct-2507-q4_k_m")
                .temperature(0.0).timeout(Duration.ofMinutes(10)).maxRetries(0).build();
        BlobStore blobs = new FilesystemBlobStore(dir);
        Glossary glossary = Glossaries.engineering();
        DomainKnowledge kb = new DomainKnowledge(SCOPE, glossary, blobs, llm, null, InstantSource.system());
        TaskHandlerRegistry registry = kb.register(new TaskHandlerRegistry(), new InMemoryAuditLog());
        ScriptedExtraction truth = new ScriptedExtraction(glossary);

        StringBuilder out = new StringBuilder("S.13 RF9: extraction with llama.cpp (Qwen3-4B-Instruct-2507 Q4_K_M) over the"
                + " business meetings, personal-data lines left out\n");
        int expected = 0;
        int found = 0;
        int extra = 0;
        int refused = 0;
        long millis = 0;
        List<String> ids = new ArrayList<>();
        for (Corpus.Meeting meeting : corpus.meetings()) {
            if (!"technical".equals(meeting.stage())) {
                continue;
            }
            String text = meeting.expected() == null || meeting.expected().sdds() == null ? "" : meeting.expected().sdds().stream()
                    .map(d -> d.title() + ". " + String.join(". ", d.covers() == null ? List.<String>of() : d.covers()) + ".")
                    .collect(Collectors.joining("\n"));
            if (text.isBlank()) {
                continue;   // a meeting that is only personal data has nothing left to extract from
            }
            var ref = blobs.put(text.getBytes(StandardCharsets.UTF_8), "docs/" + meeting.id()).toCompletableFuture().join();
            ObjectNode extract = scoped();
            extract.putObject("meeting").put("meetingId", meeting.id());
            extract.put("textRef", ref.toString()).put("schema", DomainKnowledge.SCHEMA).put("glossary", "engineering@1")
                    .put("revision", 1);
            long start = System.nanoTime();
            JsonNode batch;
            try {
                batch = run(registry, "extract-knowledge", extract);
            } catch (RuntimeException e) {
                refused++;
                Throwable cause = e.getCause() == null ? e : e.getCause();
                out.append(String.format("  %-16s refused (%s: %s)%n", meeting.id(), cause.getClass().getSimpleName(),
                        String.valueOf(cause.getMessage()).lines().findFirst().orElse("")));
                continue;
            }
            long took = (System.nanoTime() - start) / 1_000_000;
            millis += took;
            String batchJson = new String(blobs.get(java.net.URI.create(batch.path("batchRef").asText()))
                    .toCompletableFuture().join(), StandardCharsets.UTF_8);
            List<String> wanted = glossary.terms().stream().filter(t -> named(truth, text, t)).map(Glossary.Term::id).toList();
            List<String> got = glossary.terms().stream().filter(t -> batchJson.contains(t.id())).map(Glossary.Term::id).toList();
            int hit = (int) wanted.stream().filter(got::contains).count();
            expected += wanted.size();
            found += hit;
            extra += (int) got.stream().filter(g -> !wanted.contains(g)).count();
            ObjectNode write = scoped();
            write.put("batchRef", batch.path("batchRef").asText());
            run(registry, "knowledge-write", write);
            ids.add(meeting.id());
            out.append(String.format("  %-16s named by the text %d, found %d, added %d, %d ms%n", meeting.id(),
                    wanted.size(), hit, got.size() - hit, took));
        }
        int answered = 0;
        for (String[] q : QUESTIONS) {
            ObjectNode ask = scoped();
            ask.put("query", q[1]).put("topK", 5);
            String items = run(registry, "knowledge-retrieve", ask).path("items").toString().toLowerCase(Locale.ROOT);
            boolean has = items.contains(q[2]);
            answered += has ? 1 : 0;
            out.append(String.format("  question \"%s\": %s%n", q[1], has ? "the answer's term is in the context" : "not found"));
        }
        out.append(String.format("  glossary terms named by the texts: %d, found by the model: %d (%.0f%%), added: %d%n",
                expected, found, expected == 0 ? 0.0 : 100.0 * found / expected, extra));
        out.append(String.format("  batches the schema or the model refused: %d of %d; mean time %d ms%n", refused,
                ids.size() + refused, ids.isEmpty() ? 0 : millis / ids.size()));
        out.append(String.format("  questions whose answer was in the context: %d of %d%n", answered, QUESTIONS.size()));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "S.13-models.txt"), out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    private static boolean named(ScriptedExtraction truth, String text, Glossary.Term term) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains(term.canonicalName().toLowerCase(Locale.ROOT))
                || term.aliases().stream().anyMatch(a -> lower.contains(a.toLowerCase(Locale.ROOT)));
    }

    private static ObjectNode scoped() {
        ObjectNode in = Json.obj();
        in.putObject("scope").put("namespace", SCOPE.namespace()).put("base", SCOPE.base());
        return in;
    }

    private JsonNode run(TaskHandlerRegistry registry, String route, ObjectNode input) {
        TaskContext ctx = new TaskContext("/do/" + route, "i1", "c-" + route, 1, input, WORKFLOW, "chn-1", Lineage.NONE);
        return registry.find(route).orElseThrow().execute(ctx).toCompletableFuture().join();
    }
}
