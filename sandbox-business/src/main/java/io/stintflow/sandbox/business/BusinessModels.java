package io.stintflow.sandbox.business;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.decision.Decision;
import io.stintflow.worker.decision.DecisionAdapter;
import io.stintflow.wire.decision.DecisionRequest;
import io.stintflow.worker.decision.FakeDecisionAdapter;
import io.stintflow.worker.decision.jev.JevDecisionAdapter;
import io.stintflow.worker.transcription.FakeTranscriber;
import io.stintflow.worker.transcription.OpenAiCompatibleTranscriber;
import io.stintflow.worker.transcription.Transcriber;

/**
 * SDD S.5, 8e: the models the Business worker uses. {@code fake} (CI): a transcriber that returns the corpus script
 * for its synthetic audio, decisions that answer the corpus's key (uncertain when the meeting is ambiguous on purpose)
 * and a writer that builds the design from the redacted text. {@code local} (the manual run, CA7): Whisper, LitJev and
 * llama.cpp's server, all on loopback, as Stint's LOCAL-STACK.md. No real provider, no real key.
 */
public record BusinessModels(Transcriber transcriber, List<DecisionAdapter> decisions, Map<String, String> adapterOf,
        ChatModel writer, String writerProvider, String writerModel) {

    public static final List<String> ROUTES = List.of("decide-meeting-type", "decide-priority", "decide-channel");

    /** The CI's models, from the corpus's answer key. */
    public static BusinessModels fakes(Corpus corpus) {
        Map<String, Transcriber.Transcript> transcripts = new HashMap<>();
        for (Corpus.Meeting meeting : corpus.meetings()) {
            // the real Whisper's text when the corpus has it (S.6), else the script it was spoken from
            List<String> texts = corpus.whisperSegments(meeting.id())
                    .orElseGet(() -> meeting.script().stream().map(Corpus.Turn::text).toList());
            List<Transcriber.Segment> segments = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                segments.add(new Transcriber.Segment(Duration.ofSeconds(4L * i), Duration.ofSeconds(4L * i + 4),
                        texts.get(i)));
            }
            transcripts.put(FakeTranscriber.sha256(Corpus.syntheticAudio(meeting.id())),
                    new Transcriber.Transcript("en", Duration.ofSeconds(4L * segments.size()), segments));
        }
        List<DecisionAdapter> adapters = List.of(
                key("decide-meeting-type", corpus, m -> TextNode.valueOf(m.expected().meetingType())),
                key("decide-priority", corpus, m -> DoubleNode.valueOf(m.expected().priority())),
                key("decide-channel", corpus, m -> TextNode.valueOf(
                        m.expected().channel() == null ? "meeting" : m.expected().channel())));
        Map<String, String> adapterOf = new HashMap<>();
        adapters.forEach(a -> adapterOf.put(a.descriptor().id().substring("key-".length()), a.descriptor().id()));
        return new BusinessModels(FakeTranscriber.scripted(transcripts), adapters, adapterOf, null, "fake",
                "fake-design-writer");
    }

    /** One decision route answering the corpus's key for the meeting in {@code input.meetingId}. */
    static FakeDecisionAdapter key(String route, Corpus corpus, Function<Corpus.Meeting, JsonNode> answer) {
        return new FakeDecisionAdapter("key-" + route, (DecisionRequest request) -> {
            Corpus.Meeting meeting = corpus.meeting(request.input().path("meetingId").asText());
            boolean ambiguous = Boolean.TRUE.equals(meeting.expected().review());
            return Decision.of(answer.apply(meeting), ambiguous && !route.equals("decide-priority") ? 0.3 : 0.9);
        });
    }

    /** The manual run's models, on loopback (8e): Whisper on CPU, LitJev on the GPU, llama.cpp on CPU. */
    public static BusinessModels local(Map<String, String> settings) {
        JevDecisionAdapter litjev = JevDecisionAdapter.builder().server(JevDecisionAdapter.Server.LITJEV)
                .baseUri(URI.create(settings.getOrDefault("litjev.base-uri", "http://127.0.0.1:8000")))
                .model(settings.getOrDefault("litjev.model", "Qwen/Qwen3.5-2B"))
                // a score decision needs its levels, low to high (Stint 3a.2b): the priority of the work
                .scoreLevels(List.of("Low: no deadline, nobody is waiting", "Medium: wanted within a quarter",
                        "High: needed this week or blocking other work"))
                .build();
        String llmModel = settings.getOrDefault("llm.model", "qwen3-4b-instruct-2507-q4_k_m");
        OpenAiChatModel llm = OpenAiChatModel.builder()
                .baseUrl(settings.getOrDefault("llm.base-uri", "http://127.0.0.1:8080") + "/v1")
                .apiKey("local").modelName(llmModel).temperature(0.0).timeout(Duration.ofMinutes(10))
                .maxRetries(0).build();
        Transcriber whisper = OpenAiCompatibleTranscriber.builder()
                .baseUri(URI.create(settings.getOrDefault("whisper.base-uri", "http://127.0.0.1:8001")))
                .model(settings.getOrDefault("whisper.model", "Systran/faster-distil-whisper-small.en"))
                .timeout(Duration.ofMinutes(20)).build();
        Map<String, String> adapterOf = new HashMap<>();
        ROUTES.forEach(r -> adapterOf.put(r, litjev.descriptor().id()));
        return new BusinessModels(whisper, List.of(litjev), adapterOf, llm, "llama.cpp", llmModel);
    }
}
