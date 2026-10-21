package io.stintflow.sandbox.privacy.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionLayer;
import io.stintflow.worker.redaction.RedactionLayer.Scope;

/**
 * SDD S.6, CA2 and CA7: the filter over what the real Whisper wrote for the 14 meetings of the corpus
 * ({@code corpus/transcripts}, from {@code scripts/transcribe-corpus.py}; no model and no Docker in the build). The
 * fixtures are checked against the audio's hash in the manifest, so a stale one fails. Then, per meeting:
 * <ul>
 *   <li>zero marker, in any form, in the redacted text — the model check does not decide that, the layers do;</li>
 *   <li>recall of 100 % per type (name, e-mail, phone) over the planted data;</li>
 *   <li>at least 90 % of the words that are not personal data survive.</li>
 * </ul>
 * It also writes {@code target/s6/S.6-CA2.txt} — what Whisper did to each datum and what the filter did to it.
 */
class RealTranscriptsTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Scope SCOPE = new Scope("i-1", Set.of(),
            new Pseudonyms("real-transcripts-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
    static final Pattern WORD = Pattern.compile("\\p{L}{3,}");

    /**
     * The residue of a planted datum in a redacted text, beyond the forms the scanner lists: a token that begins like
     * the synthetic marker ("synth", "sinthe"), the synthetic surname, or the marker's id as a number. Whisper garbles
     * the marker itself ("Sinthe-I-2-1-131", "2016" for "2061"), so the scanner alone would call a half-redacted name
     * clean.
     */
    static List<String> residue(Corpus.Meeting m, String redacted) {
        List<String> found = new ArrayList<>();
        var tokens = Pattern.compile("(?i)\\b(?:synth|sinth)\\w*|rivera").matcher(redacted);
        while (tokens.find()) {
            found.add(tokens.group());
        }
        for (Corpus.Pii pii : m.pii()) {
            String id = pii.marker().substring(pii.marker().length() - 4);
            if (Pattern.compile("(?<!\\d)" + id + "(?!\\d)").matcher(redacted).find()) {
                found.add("id " + id);
            }
        }
        return found;
    }

    static JsonNode fixture(Corpus.Meeting m) throws IOException {
        return JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(m.id() + ".json").toFile());
    }

    static Set<String> words(String text) {
        Set<String> out = new java.util.HashSet<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void the_fixtures_are_the_corpus_audio_and_repeatable() throws IOException {
        for (JsonNode entry : CORPUS.manifest().path("meetings")) {
            JsonNode f = JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(entry.path("id").asText() + ".json").toFile());
            assertThat(f.path("audioSha256").asText()).as(entry.path("id").asText())
                    .isEqualTo(entry.path("audio").path("sha256").asText());
            assertThat(f.path("repeatable").asBoolean()).as(entry.path("id").asText() + " repeatable").isTrue();
            assertThat(f.path("text").asText()).isNotBlank();
        }
        assertThat(CORPUS.meetings()).hasSize(14);
    }

    @Test
    void nothing_planted_survives_the_filter_on_real_speech_to_text() throws IOException {
        LayeredRedactor withModel = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), new CorpusKeyCheck(CORPUS), 0.5);
        LayeredRedactor layersOnly = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), null, 0.5);
        Map<String, int[]> recall = new TreeMap<>();        // kind -> {planted, still there}
        List<String> escaped = new ArrayList<>();
        StringBuilder report = new StringBuilder("S.6 CA2/CA7: the layered filter over the real Whisper transcripts of the corpus\n")
                .append("  (fixtures corpus/transcripts, distil-small.en; layers: spoken, patterns, names, presentation)\n");
        double worstPreservation = 1.0;
        int doubts = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            String raw = fixture(m).path("text").asText();
            RedactionLayer.Result result = layersOnly.redact(raw, SCOPE);
            RedactionLayer.Result judged = withModel.redact(raw, SCOPE);
            doubts += judged.doubt() ? 1 : 0;
            List<String> residual = residue(m, result.text());
            if (!residual.isEmpty()) {
                escaped.add(m.id() + " residue " + residual + " in [" + result.text() + "]");
            }
            report.append("  ").append(m.id()).append(" residue after the filter: ").append(residual.isEmpty() ? "none"
                    : residual).append('\n');
            report.append("  ").append(m.id()).append(": ").append(m.pii().size()).append(" planted; layer counts ")
                    .append(result.counts()).append(judged.doubt() ? "; IN DOUBT" : "").append('\n');
            for (Corpus.Pii pii : m.pii()) {
                boolean writtenSurvivesInRaw = raw.contains(pii.written());
                boolean scannerSeesRaw = CORPUS.leaksIn(raw).stream().anyMatch(l -> l.startsWith(pii.marker()));
                boolean left = CORPUS.leaksIn(result.text()).stream().anyMatch(l -> l.startsWith(pii.marker()));
                int[] counts = recall.computeIfAbsent(pii.kind(), k -> new int[2]);
                counts[0]++;
                counts[1] += left ? 1 : 0;
                if (left) {
                    escaped.add(m.id() + " " + pii.marker() + " (" + pii.kind() + ")");
                }
                report.append(String.format("      %s %-5s written form in the raw text: %-3s, scanner sees it raw: %-3s, left after the filter: %s%n",
                        pii.marker(), pii.kind(), writtenSurvivesInRaw ? "yes" : "no", scannerSeesRaw ? "yes" : "no",
                        left ? "YES" : "no"));
            }
            Set<String> personal = new java.util.HashSet<>();
            m.pii().forEach(p -> {
                personal.addAll(words(p.written()));
                personal.addAll(words(p.marker()));
                p.spoken().forEach(s -> personal.addAll(words(s)));
            });
            Set<String> before = words(raw);
            before.removeAll(personal);
            Set<String> after = words(result.text());
            long kept = before.stream().filter(after::contains).count();
            double preserved = before.isEmpty() ? 1.0 : (double) kept / before.size();
            worstPreservation = Math.min(worstPreservation, preserved);
            report.append(String.format("      words kept (not personal): %d of %d (%.0f%%)%n", kept, before.size(),
                    preserved * 100));
            {
                report.append("      raw:      ").append(raw).append('\n').append("      redacted: ")
                        .append(result.text()).append('\n');
            }
            if (preserved < 0.9) {
                escaped.add(m.id() + " preservation " + String.format("%.0f%%", preserved * 100));
            }
        }
        report.append("  recall by type (planted / left after the filter):\n");
        recall.forEach((kind, c) -> report.append(String.format("    %-6s %d planted, %d left -> recall %.0f%%%n", kind, c[0], c[1],
                100.0 * (c[0] - c[1]) / c[0])));
        report.append(String.format("  worst preservation %.0f%%; redactions in doubt with the model check: %d of %d%n",
                worstPreservation * 100, doubts, CORPUS.meetings().size()));
        Path out = ROOT.resolve("sandbox-privacy/target/s6");
        Files.createDirectories(out);
        Files.writeString(out.resolve("S.6-CA2.txt"), report.toString(), StandardCharsets.UTF_8);

        assertThat(escaped).as("planted data that survived the filter on real speech-to-text").isEmpty();
        recall.forEach((kind, c) -> assertThat(c[1]).as("left after the filter, " + kind).isZero());
        assertThat(recall.keySet()).containsExactlyInAnyOrder("name", "email", "phone");
        assertThat(doubts).as("no redaction in doubt: the model check has nothing left to flag").isZero();
    }
}
