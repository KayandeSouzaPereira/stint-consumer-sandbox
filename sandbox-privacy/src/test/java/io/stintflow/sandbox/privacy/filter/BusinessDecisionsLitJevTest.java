package io.stintflow.sandbox.privacy.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.wire.decision.DecisionKind;
import io.stintflow.wire.decision.DecisionOption;
import io.stintflow.wire.decision.DecisionRequest;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.decision.Decision;
import io.stintflow.worker.decision.DecisionAdapter;
import io.stintflow.worker.decision.DecisionTask;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionLayer.Scope;

/**
 * SDD S.8, RF8 and 8g (lives in the privacy module because it needs the filter; the manual run, {@code -Dbusiness.litjev=true}, LitJev on 127.0.0.1:8000): what the local model
 * decides — meeting type, channel, priority — over the seven business scripts as Whisper really wrote them and the
 * S.6 filter redacted them, against the corpus key. A report, not a criterion: a 2B model is weak, and the workflow's
 * safety is the review of an uncertain answer, not the model's accuracy.
 */
@EnabledIfSystemProperty(named = "business.litjev", matches = "true")
class BusinessDecisionsLitJevTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Scope SCOPE = new Scope("i-1", Set.of(),
            new Pseudonyms("business-litjev-secret-0123456789".getBytes(StandardCharsets.UTF_8)));

    @Test
    void the_local_model_decides_over_the_real_redacted_transcripts() throws Exception {
        DecisionAdapter litjev = io.stintflow.worker.decision.jev.JevDecisionAdapter.builder()
                .server(io.stintflow.worker.decision.jev.JevDecisionAdapter.Server.LITJEV)
                .baseUri(java.net.URI.create("http://127.0.0.1:8000")).model("Qwen/Qwen3.5-2B")
                .scoreLevels(List.of("Low: no deadline, nobody is waiting", "Medium: wanted within a quarter",
                        "High: needed this week or blocking other work"))
                .build();
        var layers = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), null, 0.5);
        List<DecisionOption> types = List.of(
                new DecisionOption("business-design", "A business meeting that designs a product or feature"),
                new DecisionOption("technical-refinement", "A technical meeting that refines a design into work"),
                new DecisionOption("out-of-scope", "Anything else: social, another project"));
        List<DecisionOption> channels = List.of(new DecisionOption("email", "Send the design by e-mail"),
                new DecisionOption("confluence", "Publish the design on Confluence"),
                new DecisionOption("meeting", "Not ready: ask for a new meeting"));
        StringBuilder report = new StringBuilder("S.8 8g: the local LitJev (Qwen3.5-2B) over the seven business scripts,"
                + " as Whisper wrote them and the S.6 filter redacted them\n");
        int typeOk = 0;
        int channelOk = 0;
        int priorityOk = 0;
        int scripts = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            if (!"business".equals(m.stage())) {
                continue;
            }
            scripts++;
            String raw = JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(m.id() + ".json").toFile())
                    .path("text").asText();
            String text = layers.redact(raw, SCOPE).text();
            var input = Json.obj().put("text", text).put("meetingId", m.id());
            Decision type = decide(litjev, new DecisionRequest(DecisionKind.CHOICE, types, input), "Is this meeting a"
                    + " business design meeting, a technical refinement, or out of scope?");
            Decision channel = decide(litjev, new DecisionRequest(DecisionKind.CHOICE, channels, input), "How should"
                    + " the design reach the team: by e-mail, on Confluence, or by a new meeting?");
            Decision priority = decide(litjev, new DecisionRequest(DecisionKind.SCORE, List.of(), input), "How urgent"
                    + " is the work this meeting asks for, from 0 (not at all) to 1 (this week)?");
            boolean t = m.expected().meetingType().equals(type.value().asText());
            String wantChannel = m.expected().channel() == null ? "meeting" : m.expected().channel();
            boolean inScope = "business-design".equals(m.expected().meetingType());
            boolean c = !inScope || wantChannel.equals(channel.value().asText());
            double unit = Math.max(0.0, Math.min(1.0, priority.value().asDouble() / 2));   // three levels: 0 to 2 (S8-1)
            boolean p = Math.abs(unit - m.expected().priority()) <= 0.2;
            typeOk += t ? 1 : 0;
            channelOk += c ? 1 : 0;
            priorityOk += p ? 1 : 0;
            report.append(String.format("  %-16s type %-20s %s  channel %-10s %s  priority %.2f = %.2f (key %.2f) %s%n", m.id(),
                    type.value().asText(), t ? "ok " : "NO ", inScope ? channel.value().asText() : "-",
                    inScope ? (c ? "ok " : "NO ") : "-  ", priority.value().asDouble(), unit, m.expected().priority(),
                    p ? "ok" : "NO"));
        }
        report.append(String.format("  type %d of %d, channel %d of %d, priority within 0.2: %d of %d%n", typeOk, scripts,
                channelOk, scripts, priorityOk, scripts));
        Path out = ROOT.resolve("sandbox-privacy/target/s8");
        Files.createDirectories(out);
        Files.writeString(out.resolve("S.8-litjev.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertThat(scripts).isEqualTo(7);
    }

    static Decision decide(DecisionAdapter adapter, DecisionRequest request, String instructions) throws Exception {
        TaskContext ctx = new TaskContext("/do/1/x", "i-1", "c-1", 1, request.input(),
                new WorkflowRef("business", "business-design", "1.0.0"), "chn-1", Lineage.NONE);
        return adapter.decide(request, new DecisionTask(ctx, instructions)).toCompletableFuture().join();
    }
}
