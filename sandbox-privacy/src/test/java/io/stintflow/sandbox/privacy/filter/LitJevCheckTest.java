package io.stintflow.sandbox.privacy.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionLayer.Scope;

/**
 * SDD S.6, 8a/8f (the manual run, {@code -Dprivacy.litjev=true}, LitJev on 127.0.0.1:8000): what the local model's
 * {@code noul} says about the 14 real Whisper transcripts, before and after the layers — "is there still personal data
 * here?" The numbers are for calibration and for the report; the model only ever adds doubt (it is the fourth layer),
 * and a model this small is weak: the test asserts the answers are probabilities, nothing about which side they fall.
 */
@EnabledIfSystemProperty(named = "privacy.litjev", matches = "true")
class LitJevCheckTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Scope SCOPE = new Scope("i-1", Set.of(),
            new Pseudonyms("litjev-check-secret-0123456789".getBytes(StandardCharsets.UTF_8)));

    @Test
    void the_local_model_answers_a_probability_for_every_real_transcript() throws Exception {
        PersonalDataCheck check = new LitJevPersonalDataCheck(URI.create("http://127.0.0.1:8000"), "Qwen/Qwen3.5-2B",
                Duration.ofMinutes(2));
        LayeredRedactor layers = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), null, 0.5);
        StringBuilder report = new StringBuilder("S.6 8f: the noul of the local LitJev (Qwen3.5-2B) over the 14 real transcripts\n")
                .append("  likelihood that personal data is left, before the layers (raw) and after them (redacted);\n")
                .append("  the threshold of the fourth layer is 0.50\n");
        int rawAbove = 0;
        int redactedAbove = 0;
        double slowest = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            String raw = JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(m.id() + ".json").toFile())
                    .path("text").asText();
            String redacted = layers.redact(raw, SCOPE).text();
            long begun = System.nanoTime();
            double before = check.likelihood(raw);
            double after = check.likelihood(redacted);
            slowest = Math.max(slowest, (System.nanoTime() - begun) / 2e9);
            assertThat(before).as(m.id() + " raw").isBetween(0.0, 1.0);
            assertThat(after).as(m.id() + " redacted").isBetween(0.0, 1.0);
            rawAbove += before >= 0.5 ? 1 : 0;
            redactedAbove += after >= 0.5 ? 1 : 0;
            report.append(String.format("  %-16s raw %.3f   redacted %.3f%s%n", m.id(), before, after,
                    after >= 0.5 ? "   <- the filter would put this in doubt" : ""));
        }
        report.append(String.format("  at or above 0.50: raw %d of %d, redacted %d of %d; slowest call %.2f s%n", rawAbove,
                CORPUS.meetings().size(), redactedAbove, CORPUS.meetings().size(), slowest));
        Path out = ROOT.resolve("sandbox-privacy/target/s6");
        Files.createDirectories(out);
        Files.writeString(out.resolve("S.6-noul.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
