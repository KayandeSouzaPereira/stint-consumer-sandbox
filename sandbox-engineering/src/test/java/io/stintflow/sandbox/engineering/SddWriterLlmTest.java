package io.stintflow.sandbox.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionLayer.Scope;
import io.stintflow.worker.redaction.TermListLayer;

/**
 * SDD S.9, RF8 and 8e (the manual run, {@code -Dengineering.llm=true}, llama.cpp on 127.0.0.1:8080): the local model
 * writes the SDD of each thing the key lists for the three scripts that end in SDDs, from the meeting's text as Whisper
 * wrote it (redacted by the reference layers: the S.6 filter lives in the privacy module), and {@link SddFormat} checks
 * the shape and how many of the key's {@code covers} the text mentions. A report, not a criterion: it asks for one SDD
 * by id and title, so it measures the format and the coverage, not the splitting of a meeting into SDDs.
 */
@EnabledIfSystemProperty(named = "engineering.llm", matches = "true")
class SddWriterLlmTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final String PROMPT = """
            You write one SDD (a design specification for one piece of work) from a technical meeting. Answer only with the SDD in Markdown, in exactly this shape:
            # SDD %s — %s

            ## 1. Análise
            <two or three sentences: what the meeting said about this work>

            ## 3. Requisitos
            - RF1 — <a requirement>
            - RF2 — <another>

            ## 5. Critérios de aceite
            - CA1 — <a check that proves RF1>
            - CA2 — <another>

            Keep the pseudonyms as they are. Meeting:
            %s
            """;

    @Test
    void the_local_model_writes_sdds_in_the_process_format() throws Exception {
        var layers = new LayeredRedactor("reference", "1", List.of(new PatternLayer(), new TermListLayer("person",
                CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker).toList())));
        Scope scope = new Scope("i-1", java.util.Set.of(),
                new Pseudonyms("sdd-llm-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
        StringBuilder report = new StringBuilder("S.9 8e: the local model (Qwen3-4B on llama.cpp) writes one SDD per thing"
                + " the key lists, from the real Whisper text\n");
        int written = 0;
        int inFormat = 0;
        double covered = 0;
        int coverTotal = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            if (!"technical".equals(m.stage()) || !"sdds".equals(m.expected().outcome())
                    || !"technical-refinement".equals(m.expected().meetingType())) {
                continue;
            }
            String raw = JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(m.id() + ".json").toFile())
                    .path("text").asText();
            String text = layers.redact(raw, scope).text();
            for (Corpus.Sdd sdd : m.expected().sdds()) {
                String md = ask(String.format(PROMPT, sdd.id(), sdd.title(), text));
                List<String> problems = SddFormat.violations(md, sdd.id());
                long hits = sdd.covers().stream().filter(c -> mentions(md, c)).count();
                written++;
                inFormat += problems.isEmpty() ? 1 : 0;
                covered += hits;
                coverTotal += sdd.covers().size();
                report.append(String.format("  %-14s format %s  covers mentioned %d of %d%s%n", sdd.id(),
                        problems.isEmpty() ? "ok " : "NO ", hits, sdd.covers().size(),
                        problems.isEmpty() ? "" : "  " + problems));
            }
        }
        report.append(String.format("  in the process's format: %d of %d SDDs; the key's covers mentioned: %.0f of %d%n",
                inFormat, written, covered, coverTotal));
        Path out = ROOT.resolve("sandbox-engineering/target/s9");
        Files.createDirectories(out);
        Files.writeString(out.resolve("S.9-llm.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertThat(written).isGreaterThan(0);
    }

    /** At least half of the cover's words (longer than three letters) appear in the text. */
    static boolean mentions(String md, String cover) {
        String lower = md.toLowerCase();
        List<String> words = java.util.Arrays.stream(cover.toLowerCase().split("[^a-z0-9]+")).filter(w -> w.length() > 3)
                .toList();
        return words.isEmpty() || words.stream().filter(lower::contains).count() * 2 >= words.size();
    }

    static String ask(String prompt) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("model", "qwen3-4b-instruct-2507-q4_k_m")
                .put("temperature", 0.0).put("max_tokens", 900);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/v1/chat/completions"))
                .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode answer = JSON.readTree(response.body());
        return answer.path("choices").path(0).path("message").path("content").asText().strip();
    }
}
