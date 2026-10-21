package io.stintflow.sandbox.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.9, RF3/RF4 and CA3/CA4: the technical representative approves or rejects each SDD. A rejected SDD is written
 * again as the next version of the same document, up to three; what stays rejected is given up and the batch that
 * goes to Delivery keeps only the approved ones. An approval nobody answers is reminded, escalated to the technical
 * lead and — if nobody answers by 72h — ends documented, never approved. The clock moves only when the test says so.
 */
class EngineeringApprovalTest {

    static final Duration PATIENCE = Duration.ofSeconds(20);
    static final Corpus.Meeting P1 = EngineeringFixture.CORPUS.meeting("p1-c1-technical");
    static final String NOTE = "s3://sandbox-documents/notes/review-1.txt";

    final EngineeringFixture fixture = new EngineeringFixture();

    InMemoryDomain domain() throws Exception {
        return new InMemoryDomain("engineering", "technical-meeting.yaml", fixture::handlers);
    }

    InMemoryDomain controlled() throws Exception {
        return InMemoryDomain.controlled("engineering", "technical-meeting.yaml", fixture::handlers);
    }

    /** The answer of {@code role} to an approval request: {@code rejected} ids are refused (with a note), the rest approved. */
    static CloudEvent answer(CloudEvent request, String role, Set<String> rejected) {
        JsonNode asked = Json.read(request.getData().toBytes());
        ObjectNode body = Json.obj().put("reviewer", "rev-test");
        ArrayNode items = body.putArray("items");
        asked.path("items").forEach(i -> items.addObject().put("id", i.asText())
                .put("verdict", rejected.contains(i.asText()) ? "rejected" : "approved"));
        if (!rejected.isEmpty()) {
            body.put("noteRef", NOTE);
        }
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create(PipelineEvents.SOURCE_PORTAL)).withType(StintEvents.TYPE_REVIEW_COMPLETED)
                .withSubject(request.getSubject()).withDataContentType("application/json").withData(Json.bytes(body))
                .withExtension(StintEvents.EXT_ROLE, role).build();
    }

    String untilApprovalAsked(InMemoryDomain d) throws Exception {
        String id = d.engine.startFromEvent(d.definition.ref(), EngineeringWorkflowsTest.recorded(d, P1))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 1, PATIENCE)).hasSize(1);
        return id;
    }

    String text(InMemoryDomain d, String ref) {
        return new String(d.blobs.get(URI.create(ref)).toCompletableFuture().join(), StandardCharsets.UTF_8);
    }

    @Test
    void a_rejected_sdd_is_written_again_as_its_next_version_and_the_approved_one_is_left_alone() throws Exception {
        try (InMemoryDomain d = domain()) {
            int[] asked = {0};
            InstanceSnapshot end = d.run(EngineeringWorkflowsTest.recorded(d, P1), r ->
                    answer(r, "tech-rep", asked[0]++ == 0 ? Set.of("p1-sdd-2") : Set.of()), PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);

            var writes = fixture.of("write-sdds");
            assertThat(writes).hasSize(2);
            assertThat(writes.get(1).input().path("round").asInt()).isEqualTo(2);
            assertThat(writes.get(1).input().path("only")).extracting(JsonNode::asText).containsExactly("p1-sdd-2");
            assertThat(writes.get(1).input().path("noteRef").asText()).as("the note goes by pointer").isEqualTo(NOTE);

            // registered: the batch and both SDDs, then only the rejected SDD again, as its v2 (the manifest is the same)
            var sdds = fixture.registered("sdd");
            assertThat(sdds).extracting(i -> i.path("documentId").asText())
                    .containsExactly("p1-sdd-1", "p1-sdd-2", "p1-sdd-2");
            assertThat(fixture.registered("sdd-batch")).hasSize(1);
            List<CloudEvent> events = d.awaitPublished(PipelineEvents.DOCUMENT_REGISTERED, 4, PATIENCE);
            assertThat(events).hasSize(4);
            JsonNode secondSdd = events.stream().map(InMemoryDomain::json).map(e -> e.path("data"))
                    .filter(e -> "rec-p1-sdd-2".equals(e.path("recordId").asText()) && e.path("version").asInt() == 2)
                    .findFirst().orElseThrow();
            assertThat(secondSdd.path("supersedes").asInt()).isEqualTo(1);
            assertThat(events.stream().map(CloudEvent::getId).distinct().count()).as("one id per version").isEqualTo(4);

            // the texts: the rejected one has its revision, still in format; the approved one is as it was
            String sdd1 = text(d, sdds.get(0).path("documentRef").asText());
            String sdd2 = text(d, sdds.get(2).path("documentRef").asText());
            assertThat(sdd2).contains("## Revisão 1");
            assertThat(sdd1).doesNotContain("Revisão");
            assertThat(SddFormat.violations(sdd2, "p1-sdd-2")).isEmpty();

            JsonNode batch = InMemoryDomain.json(d.awaitPublished(PipelineEvents.SDD_BATCH_APPROVED, 1, PATIENCE)
                    .getFirst()).path("data");
            assertThat(batch.path("sddIds")).extracting(JsonNode::asText).containsExactly("p1-sdd-1", "p1-sdd-2");
            assertThat(batch.path("recordIds")).hasSize(3);
            EngineeringWorkflowsTest.assertContractAndNoMarker(d);
        }
    }

    @Test
    void what_stays_rejected_after_three_versions_is_given_up_and_the_batch_keeps_the_approved() throws Exception {
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(EngineeringWorkflowsTest.recorded(d, P1), r ->
                    answer(r, "tech-rep", Set.of("p1-sdd-2")), PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);

            assertThat(fixture.of("write-sdds")).as("the first writing and two rewrites").hasSize(3);
            assertThat(fixture.of("finalize-batch")).hasSize(1);
            assertThat(fixture.of("finalize-batch").getFirst().input().path("keep")).extracting(JsonNode::asText)
                    .containsExactly("p1-sdd-1");

            JsonNode batch = InMemoryDomain.json(d.awaitPublished(PipelineEvents.SDD_BATCH_APPROVED, 1, PATIENCE)
                    .getFirst()).path("data");
            assertThat(batch.path("sddIds")).extracting(JsonNode::asText).containsExactly("p1-sdd-1");
            assertThat(batch.path("recordIds")).extracting(JsonNode::asText)
                    .containsExactly("rec-p1-c1-batch", "rec-p1-sdd-1");
            JsonNode manifest = Json.read(d.blobs.get(URI.create(batch.path("batchRef").asText()))
                    .toCompletableFuture().join());
            assertThat(manifest.path("sdds")).extracting(s -> s.path("sddId").asText()).containsExactly("p1-sdd-1");
            // the batch is registered twice: as first written, and trimmed to the approved SDD (its v2)
            assertThat(fixture.registered("sdd-batch")).hasSize(2);
            EngineeringWorkflowsTest.assertContractAndNoMarker(d);
        }
    }

    @Test
    void when_no_sdd_is_approved_there_is_no_batch() throws Exception {
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(EngineeringWorkflowsTest.recorded(d, P1), r ->
                    answer(r, "tech-rep", Set.of("p1-sdd-1", "p1-sdd-2")), PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(end.position()).endsWith("/rejected");
            assertThat(d.published(PipelineEvents.SDD_BATCH_APPROVED)).isEmpty();
            assertThat(fixture.of("finalize-batch")).isEmpty();
            assertThat(fixture.of("write-timeout-report")).isEmpty();
        }
    }

    @Test
    void an_approval_nobody_answers_is_reminded_escalated_and_ends_documented_never_approved() throws Exception {
        try (InMemoryDomain d = controlled()) {
            String id = untilApprovalAsked(d);
            assertThat(d.published(StintEvents.TYPE_REVIEW_REQUESTED).getFirst().getExtension(StintEvents.EXT_ROLE))
                    .isEqualTo("tech-rep");

            d.advance(Duration.ofHours(40));
            assertThat(d.awaitPublished(StintEvents.TYPE_REVIEW_REMINDER, 1, PATIENCE)).hasSize(1);
            d.advance(Duration.ofHours(8));
            CloudEvent escalated = d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 2, PATIENCE).get(1);
            assertThat(escalated.getExtension(StintEvents.EXT_ROLE)).isEqualTo("tech-lead");
            assertThat(InMemoryDomain.json(escalated).path("data").path("escalatedFrom").asText())
                    .isEqualTo("tech-rep");

            d.advance(Duration.ofHours(24));
            InstanceSnapshot end = d.drive(id, r -> null, PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(end.position()).as("ended on the documented timeout").endsWith("/timedOut");
            assertThat(d.published(PipelineEvents.SDD_BATCH_APPROVED)).as("never approved").isEmpty();

            assertThat(fixture.registered("report")).hasSize(1);
            assertThat(fixture.registered("report").getFirst().path("documentId").asText())
                    .isEqualTo("p1-c1-batch-approval-timeout");
            assertThat(fixture.of("send-email")).extracting(c -> c.input().path("template").asText() + ":"
                    + c.input().path("toRole").asText()).containsExactlyInAnyOrder("approval-timeout:tech-lead",
                            "approval-timeout:business-lead");
            assertThat(d.audit.of(id).toCompletableFuture().get(5, TimeUnit.SECONDS)).filteredOn(a ->
                    "review".equals(a.kind())).extracting(a -> a.outcome())
                    .contains("requested", "reminded", "escalated");
            EngineeringWorkflowsTest.assertContractAndNoMarker(d);
        }
    }

    @Test
    void the_technical_lead_answers_the_escalation_and_the_batch_is_approved() throws Exception {
        try (InMemoryDomain d = controlled()) {
            String id = untilApprovalAsked(d);
            d.advance(Duration.ofHours(40));
            d.advance(Duration.ofHours(8));
            CloudEvent escalated = d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 2, PATIENCE).get(1);

            InstanceSnapshot end = d.drive(id, r -> r.getId().equals(escalated.getId())
                    ? answer(r, "tech-lead", Set.of()) : null, PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.awaitPublished(PipelineEvents.SDD_BATCH_APPROVED, 1, PATIENCE)).hasSize(1);
            assertThat(fixture.registered("report")).isEmpty();
        }
    }
}
