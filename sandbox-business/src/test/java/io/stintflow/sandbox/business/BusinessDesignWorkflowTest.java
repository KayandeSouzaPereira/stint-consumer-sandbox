package io.stintflow.sandbox.business;

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
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.spi.BlobStore;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.ExecutionTracer;
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
 * SDD S.5, 8h: {@code business-design} on Stint's in-memory engine with the CI's models and stand-ins of the shared
 * services: a design meeting ends in {@code design-published}, out of scope ends quietly, an ambiguous one goes to the
 * business representative, and an answer from another role does not move it (CA3, the engine's side). Every event it
 * publishes keeps the v1 contract and holds no marker.
 */
class BusinessDesignWorkflowTest {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));
    static final PipelineContracts CONTRACTS = new PipelineContracts();

    InMemoryDomain domain() throws Exception {
        return new InMemoryDomain("business", "business-design.yaml", BusinessDesignWorkflowTest::handlers);
    }

    /** The domain's own routes (CI models) and stand-ins of privacy, records and channels. */
    static TaskHandlerRegistry handlers(BlobStore blobs) {
        List<String> markers = CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker)
                .toList();
        return BusinessEngine.handlers(BusinessModels.fakes(CORPUS), blobs, blobs, ExecutionTracer.NOOP)
                .register("redactor", new RedactionHandler(new LayeredRedactor("test", "1", List.of(new PatternLayer(),
                        new TermListLayer("person", markers))), new Pseudonyms("test-secret-0123456789"
                                .getBytes(StandardCharsets.UTF_8)), blobs))
                .register("register-document", ctx -> CompletableFuture.completedFuture(Json.obj()
                        .put("recordId", "rec-" + ctx.input().path("documentId").asText()).put("version", 1)
                        .put("sha256", "0".repeat(64)).put("recordRef", "record://x/1")))
                .register("publish-page", ctx -> CompletableFuture.completedFuture(Json.obj().put("id", "page-1")
                        .put("url", "http://127.0.0.1/pages/1")))
                .register("send-email", ctx -> CompletableFuture.completedFuture(Json.obj().put("sent", true)));
    }

    /** The recorder's event for a corpus meeting, its synthetic audio stored first. */
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

    /** The portal's answer, as {@code sandbox-channels} publishes it. */
    static CloudEvent answer(CloudEvent request, String role, JsonNode data) {
        ObjectNode body = data.deepCopy();
        body.put("reviewer", "rev-test");
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create(PipelineEvents.SOURCE_PORTAL)).withType(StintEvents.TYPE_REVIEW_COMPLETED)
                .withSubject(request.getSubject()).withDataContentType("application/json").withData(Json.bytes(body))
                .withExtension(StintEvents.EXT_ROLE, role).build();
    }

    /** The business representative answering as the corpus key says. */
    static CloudEvent asTheKey(CloudEvent request, Corpus.Meeting m) {
        JsonNode data = Json.read(request.getData().toBytes());
        if ("choice".equals(data.path("kind").asText())) {
            boolean channel = data.path("options").toString().contains("confluence");
            return answer(request, "business-rep", Json.obj().put("value",
                    channel ? m.expected().channel() : m.expected().meetingType()));
        }
        return answer(request, "business-rep", Json.obj().put("verdict", "approved"));
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
    void a_design_meeting_ends_in_design_published_with_one_chain() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("p1-c1-business");
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));

            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            CloudEvent published = d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, Duration.ofSeconds(5)).getFirst();
            assertThat(published.getExtension(PipelineEvents.EXT_PROJECT)).isEqualTo("p1");
            assertThat(published.getExtension(PipelineEvents.EXT_CYCLE)).isEqualTo("1");
            assertThat(published.getSubject()).isEqualTo("p1-c1-design");
            assertThat(Json.read(published.getData().toBytes()).path("channel").asText()).isEqualTo("confluence");
            assertThat(d.published(PipelineEvents.DOCUMENT_REGISTERED)).hasSize(1);
            assertThat(d.bus.published()).allMatch(e -> PipelineEvents.chainIdOf("p1")
                    .equals(e.getExtension(StintEvents.EXT_CHAIN_ID)));
            assertThat(d.published(PipelineEvents.REVIEW_REQUESTED)).singleElement()
                    .matches(e -> "business".equals(e.getExtension(PipelineEvents.EXT_DOMAIN)));
            assertContractAndNoMarker(d);
        }
    }

    @Test
    void an_out_of_scope_meeting_ends_without_a_design() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("o1-c1-business");
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.published(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();
            assertThat(d.published(PipelineEvents.DOCUMENT_REGISTERED)).isEmpty();
        }
    }

    @Test
    void an_ambiguous_meeting_goes_to_the_business_representative() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("a1-c1-business");
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(recorded(d, m), r -> asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.published(PipelineEvents.REVIEW_REQUESTED)).hasSize(3);   // type, channel, approval
            assertThat(d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, Duration.ofSeconds(5))).hasSize(1);
            assertContractAndNoMarker(d);
        }
    }

    @Test
    void an_answer_from_another_role_does_not_move_the_review() throws Exception {
        Corpus.Meeting m = CORPUS.meeting("p1-c1-business");
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot waiting = d.run(recorded(d, m),
                    r -> answer(r, "tech-rep", Json.obj().put("verdict", "approved")), Duration.ofSeconds(5));
            assertThat(waiting.status()).isEqualTo(InstanceStatus.WAITING);
            assertThat(d.published(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();
            assertThat(d.audit.of(waiting.instanceId()).toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .anyMatch(a -> "review".equals(a.kind()) && "wrong-role".equals(a.outcome()));

            CloudEvent request = d.published(PipelineEvents.REVIEW_REQUESTED).getFirst();
            d.engine.onDomainEvent(asTheKey(request, m), Duration.ofMinutes(5)).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(d.drive(waiting.instanceId(), r -> null, Duration.ofSeconds(30)).status())
                    .isEqualTo(InstanceStatus.COMPLETED);
        }
    }
}
