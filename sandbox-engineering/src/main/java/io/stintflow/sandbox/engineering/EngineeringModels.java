package io.stintflow.sandbox.engineering;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.wire.decision.DecisionRequest;
import io.stintflow.worker.decision.Decision;
import io.stintflow.worker.decision.DecisionAdapter;
import io.stintflow.worker.decision.FakeDecisionAdapter;
import io.stintflow.worker.transcription.FakeTranscriber;
import io.stintflow.worker.transcription.Transcriber;

/**
 * SDD S.5, 8e: the CI's models of the Engineering domain — the corpus script as the transcript of its synthetic
 * audio, and decisions that answer the corpus's key (uncertain when the meeting is ambiguous on purpose). The local
 * models of the manual run are the Business domain's (CA7); Engineering's are S.9.
 */
public record EngineeringModels(Transcriber transcriber, List<DecisionAdapter> decisions, Map<String, String> adapterOf) {

    public static EngineeringModels fakes(Corpus corpus) {
        Map<String, Transcriber.Transcript> transcripts = new HashMap<>();
        for (Corpus.Meeting meeting : corpus.meetings()) {
            List<Transcriber.Segment> segments = new ArrayList<>();
            for (int i = 0; i < meeting.script().size(); i++) {
                segments.add(new Transcriber.Segment(Duration.ofSeconds(4L * i), Duration.ofSeconds(4L * i + 4),
                        meeting.script().get(i).text()));
            }
            transcripts.put(FakeTranscriber.sha256(Corpus.syntheticAudio(meeting.id())),
                    new Transcriber.Transcript("en", Duration.ofSeconds(4L * segments.size()), segments));
        }
        List<DecisionAdapter> adapters = List.of(
                key("decide-meeting-type", corpus, m -> TextNode.valueOf(m.expected().meetingType())),
                key("decide-priority", corpus, m -> DoubleNode.valueOf(m.expected().priority())),
                key("decide-outcome", corpus, m -> TextNode.valueOf(
                        m.expected().outcome() == null ? "questions" : m.expected().outcome())),
                key("decide-blocking", corpus, m -> BooleanNode.valueOf(
                        Boolean.TRUE.equals(m.expected().blockingQuestion()))));
        Map<String, String> adapterOf = new HashMap<>();
        adapters.forEach(a -> adapterOf.put(a.descriptor().id().substring("key-".length()), a.descriptor().id()));
        return new EngineeringModels(FakeTranscriber.scripted(transcripts), adapters, adapterOf);
    }

    /** One decision route answering the corpus's key for the meeting in {@code input.meetingId}. */
    static FakeDecisionAdapter key(String route, Corpus corpus, Function<Corpus.Meeting, JsonNode> answer) {
        return new FakeDecisionAdapter("key-" + route, (DecisionRequest request) -> {
            Corpus.Meeting meeting = corpus.meeting(request.input().path("meetingId").asText());
            boolean ambiguous = Boolean.TRUE.equals(meeting.expected().review());
            boolean reviewable = route.equals("decide-meeting-type") || route.equals("decide-outcome");
            return Decision.of(answer.apply(meeting), ambiguous && reviewable ? 0.3 : 0.9);
        });
    }
}
