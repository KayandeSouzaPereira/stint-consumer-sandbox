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
 * SDD S.9, RF8 and 8e (the manual run, {@code -Dengineering.litjev=true}, LitJev on 127.0.0.1:8000): what the local
 * model decides — meeting type, outcome, the blocking question — over the seven technical scripts as Whisper really
 * wrote them and the S.6 filter redacted them, against the corpus key. A report, not a criterion.
 */
@EnabledIfSystemProperty(named = "engineering.litjev", matches = "true")
class EngineeringDecisionsLitJevTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Scope SCOPE = new Scope("i-1", Set.of(),
            new Pseudonyms("engineering-litjev-secret-0123456789".getBytes(StandardCharsets.UTF_8)));

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
        List<DecisionOption> outcomes = List.of(
                new DecisionOption("sdds", "The meeting decided the work: write the SDDs"),
                new DecisionOption("questions", "The meeting needs answers from Business first"));
        List<DecisionOption> blocking = List.of(
                new DecisionOption("true", "Some question must be answered by Business before any SDD can be written"),
                new DecisionOption("false", "Nothing blocks writing the SDDs"));
        StringBuilder report = new StringBuilder("S.9 8e: the local LitJev (Qwen3.5-2B) over the seven technical scripts,"
                + " as Whisper wrote them and the S.6 filter redacted them\n");
        int typeOk = 0;
        int outcomeOk = 0;
        int blockingOk = 0;
        int scripts = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            if (!"technical".equals(m.stage())) {
                continue;
            }
            scripts++;
            String raw = JSON.readTree(ROOT.resolve("corpus/transcripts").resolve(m.id() + ".json").toFile())
                    .path("text").asText();
            var input = Json.obj().put("text", layers.redact(raw, SCOPE).text()).put("meetingId", m.id());
            Decision type = decide(litjev, new DecisionRequest(DecisionKind.CHOICE, types, input), "Is this meeting a"
                    + " business design meeting, a technical refinement, or out of scope?");
            Decision outcome = decide(litjev, new DecisionRequest(DecisionKind.CHOICE, outcomes, input), "Did the"
                    + " meeting decide the work (write the SDDs), or does it need answers from Business first?");
            Decision block = decide(litjev, new DecisionRequest(DecisionKind.BOOLEAN, blocking, input), "Is there a"
                    + " question Business must answer before any SDD can be written?");
            boolean inScope = "technical-refinement".equals(m.expected().meetingType());
            boolean t = m.expected().meetingType().equals(type.value().asText());
            boolean o = !inScope || m.expected().outcome().equals(outcome.value().asText());
            boolean b = !inScope || Boolean.TRUE.equals(m.expected().blockingQuestion()) == block.value().asBoolean();
            typeOk += t ? 1 : 0;
            outcomeOk += o ? 1 : 0;
            blockingOk += b ? 1 : 0;
            report.append(String.format("  %-16s type %-20s %s  outcome %-9s %s  blocking %-5s %s%n", m.id(),
                    type.value().asText(), t ? "ok " : "NO ", inScope ? outcome.value().asText() : "-",
                    inScope ? (o ? "ok " : "NO ") : "-  ", inScope ? block.value().asText() : "-",
                    inScope ? (b ? "ok" : "NO") : "-"));
        }
        report.append(String.format("  type %d of %d, outcome %d of %d, blocking question %d of %d%n", typeOk, scripts,
                outcomeOk, scripts, blockingOk, scripts));
        Path out = ROOT.resolve("sandbox-privacy/target/s9");
        Files.createDirectories(out);
        Files.writeString(out.resolve("S.9-litjev.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertThat(scripts).isEqualTo(7);
    }

    static Decision decide(DecisionAdapter adapter, DecisionRequest request, String instructions) {
        TaskContext ctx = new TaskContext("/do/1/x", "i-1", "c-1", 1, request.input(),
                new WorkflowRef("engineering", "technical-meeting", "1.0.0"), "chn-1", Lineage.NONE);
        return adapter.decide(request, new DecisionTask(ctx, instructions)).toCompletableFuture().join();
    }
}
