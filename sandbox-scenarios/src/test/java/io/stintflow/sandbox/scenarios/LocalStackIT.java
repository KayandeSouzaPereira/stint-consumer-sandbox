package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Portal;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.5, CA7 (the manual run, {@code -Dscenarios.local=true}): one corpus meeting, in real audio (Kokoro), through
 * the Business domain on the local models — Whisper transcribes it, LitJev decides its type, priority and channel, the
 * local generator writes the design — all on loopback (infra/local-stack.sh), with the scripted reviewer answering
 * through the portal. It records the latency of each step, the GPU's memory (nvidia-smi) and how Whisper wrote the
 * planted personal data, in the forms the scanner must know (8d). Synthetic data only; no key; nothing leaves the host.
 */
@EnabledIfSystemProperty(named = "scenarios.local", matches = "true")
class LocalStackIT extends ScenarioBase {

    static final Corpus CORPUS = Corpus.load(Scenario.root().resolve("corpus"));
    static final String MEETING = System.getProperty("scenarios.local.meeting", "p1-c1-business");
    static final Duration PATIENCE = Duration.ofMinutes(25);

    @Test
    void one_meeting_in_real_audio_goes_through_whisper_litjev_and_the_local_generator() throws Exception {
        Corpus.Meeting meeting = CORPUS.meeting(MEETING);
        Map<String, String> local = Map.of("business.models", "local");
        start("privacy", Module.PRIVACY, Map.of());
        start("records", Module.RECORDS, Map.of());
        start("channels", Module.CHANNELS, Map.of("channels.recorder.audio", "corpus", "channels.recorder.auto", "false"));
        App business = start("business", Module.BUSINESS, local);
        Portal portal = new Portal(Module.CHANNELS.port());

        long peakMiB = gpuMiB();
        long baselineMiB = peakMiB;
        Instant begun = Instant.now();
        assertThat(portal.record(MEETING)).isEqualTo(202);
        note("recorded %s (real audio)", MEETING);
        List<String> answered = new ArrayList<>();
        Instant[] published = new Instant[1];
        spy.until("design-published (or the meeting ended out of scope)", () -> {
            portal.answerAsTheKey(CORPUS, PipelineEvents.ROLE_BUSINESS).forEach(a -> {
                answered.add(a);
                note("answered %s", a);
            });
            if (published[0] == null && spy.events().stream().anyMatch(e -> e.type().equals(PipelineEvents.DESIGN_PUBLISHED))) {
                published[0] = Instant.now();
            }
            return published[0] != null;
        }, PATIENCE);
        long total = Duration.between(begun, published[0]).toSeconds();
        peakMiB = Math.max(peakMiB, gpuMiB());

        // --- per-step latency from the Business log (decisions and calls are logged with ids and numbers only) ---
        Map<String, String> steps = new TreeMap<>();
        for (String line : business.lines()) {
            try {
                JsonNode j = Spy.JSON.readTree(line);
                String m = j.path("message").asText();
                if (m.startsWith("Recorded the output of ")) {
                    steps.put(j.path("timestamp").asText().substring(11, 23) + " " + m.substring(23, m.indexOf(" (")),
                            m.contains("\"confidence\"") ? m.substring(m.indexOf("{")).replaceAll("\"provenance\".*", "}")
                                    : "");
                }
                if (m.startsWith("Decision usage")) {
                    steps.put(j.path("timestamp").asText().substring(11, 23) + " usage",
                            m.substring(m.indexOf("adapter=")));
                }
            } catch (Exception ignored) {
                // not a JSON line
            }
        }

        // --- how Whisper wrote the planted personal data (the raw transcript is a restricted blob; this reads it as ops) ---
        StringBuilder forms = new StringBuilder();
        for (var o : floci.s3.listObjectsV2(r -> r.bucket("sandbox-meetings-raw")).contents()) {
            if (!o.key().contains("transcript")) {
                continue;
            }
            JsonNode transcript = Spy.JSON.readTree(floci.s3.getObjectAsBytes(r -> r.bucket("sandbox-meetings-raw")
                    .key(o.key())).asByteArray());
            StringBuilder text = new StringBuilder();
            transcript.path("segments").forEach(s -> text.append(s.path("text").asText().strip()).append(' '));
            forms.append("  transcript ").append(o.key()).append(" (").append(transcript.path("segments").size())
                    .append(" segments):\n");
            for (Corpus.Pii pii : meeting.pii()) {
                forms.append("    ").append(pii.marker()).append(" (").append(pii.kind()).append("), written form ")
                        .append(text.toString().contains(pii.written()) ? "SURVIVES" : "is gone").append("; scanner finds it: ")
                        .append(CORPUS.leaksIn(text.toString()).stream().anyMatch(l -> l.startsWith(pii.marker())))
                        .append('\n');
            }
            forms.append("    transcript text, as written by Whisper:\n      ").append(text.toString().strip()).append('\n');
        }

        StringBuilder e = new StringBuilder("S.5 CA7: one corpus meeting in real audio, on the local model stack\n")
                .append("  meeting ").append(MEETING).append(" (").append(meeting.expected().meetingType())
                .append(", key channel ").append(meeting.expected().channel()).append(")\n")
                .append("  stack: Whisper distil-small.en (CPU, :8001), LitJev Qwen3.5-2B fp16 (GPU, :8000), llama.cpp Qwen3-4B Q4 (CPU, :8080)\n")
                .append("  total, recorded -> design-published: ").append(total).append(" s; reviews answered: ")
                .append(answered.size()).append('\n').append("  GPU memory (nvidia-smi): baseline ").append(baselineMiB)
                .append(" MiB, peak seen ").append(peakMiB).append(" MiB of 8192\n  steps (Business log, UTC):\n");
        steps.forEach((k, v) -> e.append("    ").append(k).append("  ").append(v).append('\n'));
        e.append("  events: ").append(spy.events().stream().map(Spy.Event::type).toList()).append('\n').append(timeline())
                .append(forms);
        Scenario.evidence("S.5-CA7-manual.txt", e.toString());
        assertThat(spy.events().stream().filter(x -> x.type().equals(PipelineEvents.DESIGN_PUBLISHED)).count()).isEqualTo(1);
    }

    /** The GPU's used memory in MiB, from nvidia-smi (0 if it is not there). */
    static long gpuMiB() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(10, TimeUnit.SECONDS);
            return Long.parseLong(out.lines().findFirst().orElse("0").strip());
        } catch (Exception ex) {
            return 0;
        }
    }

    static URI unused() {
        return URI.create("http://localhost");
    }
}
