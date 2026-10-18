package io.stintflow.sandbox.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.RetentionClass;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionHandler;
import io.stintflow.worker.redaction.TermListLayer;

/**
 * SDD S.5, 8h: Engineering's two workflows on Stint's in-memory engine with the CI's models and stand-ins of the shared
 * services. A design asks for its technical meeting; a technical meeting ends in an approved batch of registered SDDs,
 * or in questions that restart the cycle; an ambiguous one goes to the technical representative. Every event keeps
 * the v1 contract and holds no marker.
 */
class EngineeringWorkflowsTest {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));
    static final PipelineContracts CONTRACTS = new PipelineContracts();

    static TaskHandlerRegistry handlers(BlobStore blobs) {
        List<String> markers = CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker)
                .toList();
        return EngineeringEngine.handlers(CORPUS, EngineeringModels.fakes(CORPUS), blobs, blobs)
                .register("redactor", new RedactionHandler(new LayeredRedactor("test", "1", List.of(new PatternLayer(),
                        new TermListLayer("person", markers))), new Pseudonyms("test-secret-0123456789"
                                .getBytes(StandardCharsets.UTF_8)), blobs))
                .register("register-document", ctx -> CompletableFuture.completedFuture(Json.obj()
                        .put("recordId", "rec-" + ctx.input().path("documentId").asText()).put("version", 1)
                        .put("sha256", "0".repeat(64)).put("recordRef", "record://x/1")))
                .register("send-email", ctx -> CompletableFuture.completedFuture(Json.obj().put("sent", true)));
    }

    static CloudEvent recorded(InMemoryDomain d, Corpus.Meeting m) throws Exception {
        URI audio = d.blobs.put(Corpus.syntheticAudio(m.id()), "audio/" + m.id() + ".wav",
                new RetentionClass("raw-audio", Duration.ofDays(7))).toCompletableFuture().get(5, TimeUnit.SECONDS);
        ObjectNode data = Json.obj().put("meetingId", m.id()).put("audioRef", audio.toString()).put("language", "en")
                .put("label", "restricted");
        return CloudEventBuilder.v1().withId("rec-" + m.id()).withSource(URI.create(PipelineEvents.SOURCE_CHANNELS))
                .withType(PipelineEvents.MEETING_RECORDED).withSubject(m.id())
                .withDataContentType("application/json").withData(Json.bytes(data))
                .withExtension(PipelineEvents.EXT_PROJECT, m.project())
                .withExtension(PipelineEvents.EXT_CYCLE, String.valueOf(m.cycle()))
                .withExtension(PipelineEvents.EXT_STAGE, m.stage())
                .withExtension(StintEvents.EXT_CHAIN_ID, PipelineEvents.chainIdOf(m.project())).build();
    }

    /** The technical representative: the key's answer to a choice, every item approved. */
    static CloudEvent asTheKey(CloudEvent request, Corpus.Meeting m) {
        JsonNode data = Json.read(request.getData().toBytes());
        ObjectNode body = Json.obj().put("reviewer", "rev-test");
        if ("choice".equals(data.path("kind").asText())) {
            boolean outcome = data.path("options").toString().contains("questions");
            body.put("value", outcome ? m.expected().outcome() : m.expected().meetingType());
        } else if (data.path("items").isEmpty()) {
            body.put("verdict", "approved");
        } else {
            ArrayNode items = body.putArray("items");
            data.path("items").forEach(i -> items.addObject().put("id", i.asText()).put("verdict", "approved"));
        }
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create(PipelineEvents.SOURCE_PORTAL)).withType(StintEvents.TYPE_REVIEW_COMPLETED)
                .withSubject(request.getSubject()).withDataContentType("application/json").withData(Json.bytes(body))
                .withExtension(StintEvents.EXT_ROLE, "tech-rep").build();
    }

    static void assertContractAndNoMarker(InMemoryDomain d) {
        for (CloudEvent e : d.bus.published()) {
            JsonNode json = InMemoryDomain.json(e);
            if (CONTRACTS.isPipelineType(e.getType())) {
                assertThat(CONTRACTS.violations(json)).as(e.getType()).isEmpty();
            }
            assertThat(CORPUS.leaksIn(json.toString())).as(e.getType()).isEmpty();
        }
    }

    @Test
    void a_design_asks_for_its_technical_meeting() throws Exception {
        try (InMemoryDomain d = new InMemoryDomain("engineering", "refinement.yaml",
                EngineeringWorkflowsTest::handlers)) {
            ObjectNode data = Json.obj().put("documentId", "p1-c1-design").put("recordId", "rec-p1-c1-design")
                    .put("version", 1).put("designRef", "s3://sandbox-documents/design/p1-c1-design.md")
                    .put("channel", "confluence");
            CloudEvent published = CloudEventBuilder.v1().withId("p1:1:design-published")
                    .withSource(URI.create(PipelineEvents.SOURCE_BUSINESS)).withType(PipelineEvents.DESIGN_PUBLISHED)
                    .withSubject("p1-c1-design").withData(Json.bytes(data))
                    .withExtension(PipelineEvents.EXT_PROJECT, "p1").withExtension(PipelineEvents.EXT_CYCLE, "1")
                    .withExtension(StintEvents.EXT_SENSITIVITY, "confidential").build();
            InstanceSnapshot end = d.run(published, r -> null, Duration.ofSeconds(10));
            assertThat(end.status()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.awaitPublished(PipelineEvents.REFINEMENT_REQUESTED, 1, Duration.ofSeconds(5))).singleElement()
                    .matches(e -> "p1".equals(e.getExtension(PipelineEvents.EXT_PROJECT)));
            assertContractAndNoMarker(d);
        }
    }

    @Test
    void a_technical_meeting_ends_in_an_approved_batch_of_registered_sdds() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("p1-c1-technical");
        try (InMemoryDomain d = new InMemoryDomain("engineering", "technical-meeting.yaml",
                EngineeringWorkflowsTest::handlers)) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            JsonNode batch = Json.read(d.awaitPublished(PipelineEvents.SDD_BATCH_APPROVED, 1, Duration.ofSeconds(5))
                    .getFirst().getData().toBytes());
            assertThat(batch.path("sddIds")).extracting(JsonNode::asText).containsExactly("p1-sdd-1", "p1-sdd-2");
            assertThat(batch.path("recordIds")).hasSize(3);
            assertThat(d.published(PipelineEvents.DOCUMENT_REGISTERED)).hasSize(3);
            JsonNode request = Json.read(d.published(PipelineEvents.REVIEW_REQUESTED).getFirst().getData().toBytes());
            assertThat(request.path("items")).extracting(JsonNode::asText).containsExactly("p1-sdd-1", "p1-sdd-2");
            assertContractAndNoMarker(d);
        }
    }

    @Test
    void a_blocking_question_sends_the_questions_and_restarts_the_cycle() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("p2-c1-technical");
        try (InMemoryDomain d = new InMemoryDomain("engineering", "technical-meeting.yaml",
                EngineeringWorkflowsTest::handlers)) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.awaitPublished(PipelineEvents.QUESTIONS_SENT, 1, Duration.ofSeconds(5))).hasSize(1);
            JsonNode sent = Json.read(d.published(PipelineEvents.QUESTIONS_SENT).getFirst().getData().toBytes());
            assertThat(sent.path("nextCycle").asInt()).isEqualTo(2);
            assertThat(sent.path("questionCount").asInt()).isEqualTo(2);
            assertThat(d.published(PipelineEvents.SDD_BATCH_APPROVED)).isEmpty();
            assertContractAndNoMarker(d);
        }
    }

    @Test
    void an_ambiguous_technical_meeting_goes_to_the_technical_representative() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("a2-c1-technical");
        try (InMemoryDomain d = new InMemoryDomain("engineering", "technical-meeting.yaml",
                EngineeringWorkflowsTest::handlers)) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.published(PipelineEvents.REVIEW_REQUESTED)).isNotEmpty()
                    .allMatch(e -> "tech-rep".equals(e.getExtension(StintEvents.EXT_ROLE)));
            assertThat(d.awaitPublished(PipelineEvents.QUESTIONS_SENT, 1, Duration.ofSeconds(5))).hasSize(1);
        }
    }
}
