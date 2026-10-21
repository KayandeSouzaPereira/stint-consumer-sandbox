package io.stintflow.sandbox.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.8, RF3/RF4 and CA2/CA3: what happens to a design after its approval is asked. No answer: a reminder at 40h, an
 * escalation to the business lead at 48h, and — if nobody answers by 72h — a documented end, never a publication. A
 * rejection with a note writes the next version of the same document, up to three. The clock moves only when the test
 * says so (no sleep).
 */
class BusinessApprovalTest {

    static final Duration PATIENCE = Duration.ofSeconds(20);
    static final Corpus.Meeting P1 = BusinessFixture.CORPUS.meeting("p1-c1-business");
    static final String NOTE = "s3://sandbox-documents/notes/review-1.txt";

    final BusinessFixture fixture = new BusinessFixture();

    InMemoryDomain domain() throws Exception {
        return InMemoryDomain.controlled("business", "business-design.yaml", fixture::handlers);
    }

    /** Starts the p1 meeting and returns its id once the first approval has been asked. */
    String untilApprovalAsked(InMemoryDomain d) throws Exception {
        String id = d.engine.startFromEvent(d.definition.ref(), BusinessDesignWorkflowTest.recorded(d, P1))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 1, PATIENCE)).hasSize(1);
        return id;
    }

    @Test
    void an_approval_nobody_answers_is_reminded_escalated_and_ends_documented_never_published() throws Exception {
        try (InMemoryDomain d = domain()) {
            String id = untilApprovalAsked(d);
            CloudEvent first = d.published(StintEvents.TYPE_REVIEW_REQUESTED).getFirst();
            assertThat(first.getExtension(StintEvents.EXT_ROLE)).isEqualTo("business-rep");

            d.advance(Duration.ofHours(40));
            assertThat(d.awaitPublished(StintEvents.TYPE_REVIEW_REMINDER, 1, PATIENCE)).hasSize(1);
            d.advance(Duration.ofHours(8));
            List<CloudEvent> requests = d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 2, PATIENCE);
            CloudEvent escalated = requests.get(1);
            assertThat(escalated.getExtension(StintEvents.EXT_ROLE)).isEqualTo("business-lead");
            assertThat(InMemoryDomain.json(escalated).path("data").path("escalatedFrom").asText())
                    .isEqualTo("business-rep");

            d.advance(Duration.ofHours(24));
            InstanceSnapshot end = d.drive(id, r -> null, PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(end.position()).as("ended on the documented timeout").endsWith("/timedOut");
            assertThat(d.published(PipelineEvents.DESIGN_PUBLISHED)).as("never published").isEmpty();

            // documented: a report in the register, a notice to every lead, and the review's audit
            assertThat(fixture.of("register-document")).hasSize(2);
            JsonNode report = fixture.of("register-document").get(1).input();
            assertThat(report.path("kind").asText()).isEqualTo("report");
            assertThat(report.path("documentId").asText()).isEqualTo("p1-c1-design-approval-timeout");
            assertThat(fixture.of("send-email")).extracting(c -> c.input().path("template").asText() + ":"
                    + c.input().path("toRole").asText()).contains("approval-timeout:business-lead",
                            "approval-timeout:tech-rep");
            assertThat(d.audit.of(id).toCompletableFuture().get(5, TimeUnit.SECONDS)).filteredOn(a ->
                    "review".equals(a.kind())).extracting(a -> a.outcome())
                    .contains("requested", "reminded", "escalated");
            List<CloudEvent> registered = d.published(PipelineEvents.DOCUMENT_REGISTERED);
            assertThat(registered).hasSize(2);
            assertThat(InMemoryDomain.json(registered.get(1)).path("data").path("kind").asText()).isEqualTo("report");
            BusinessDesignWorkflowTest.assertContractAndNoMarker(d);
        }
    }

    @Test
    void the_business_lead_answers_the_escalation_and_the_design_is_published() throws Exception {
        try (InMemoryDomain d = domain()) {
            String id = untilApprovalAsked(d);
            d.advance(Duration.ofHours(40));
            d.advance(Duration.ofHours(8));
            CloudEvent escalated = d.awaitPublished(StintEvents.TYPE_REVIEW_REQUESTED, 2, PATIENCE).get(1);

            InstanceSnapshot end = d.drive(id, r -> r.getId().equals(escalated.getId())
                    ? BusinessDesignWorkflowTest.answer(r, "business-lead", Json.obj().put("verdict", "approved"))
                    : null, PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, PATIENCE)).hasSize(1);
            assertThat(fixture.of("send-email")).extracting(c -> c.input().path("template").asText())
                    .doesNotContain("approval-timeout");
        }
    }

    @Test
    void a_rejection_with_a_note_writes_the_next_version_of_the_same_design() throws Exception {
        try (InMemoryDomain d = domain()) {
            int[] asked = {0};
            InstanceSnapshot end = d.run(BusinessDesignWorkflowTest.recorded(d, P1), r -> {
                boolean first = asked[0]++ == 0;
                return BusinessDesignWorkflowTest.answer(r, "business-rep", first
                        ? Json.obj().put("verdict", "rejected").put("noteRef", NOTE)
                        : Json.obj().put("verdict", "approved"));
            }, PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);

            var writes = fixture.of("write-design");
            assertThat(writes).hasSize(2);
            assertThat(writes.get(0).input().path("round").asInt()).isEqualTo(1);
            assertThat(writes.get(1).input().path("round").asInt()).isEqualTo(2);
            assertThat(writes.get(1).input().path("noteRef").asText()).as("the note goes by pointer").isEqualTo(NOTE);
            assertThat(writes.get(1).input().path("previousRef").asText()).isNotBlank();

            var registers = fixture.of("register-document");
            assertThat(registers).hasSize(2);
            assertThat(registers.get(1).input().path("documentId").asText())
                    .isEqualTo(registers.get(0).input().path("documentId").asText());
            assertThat(registers.get(0).input().path("priority").asDouble()).isEqualTo(0.8);
            List<CloudEvent> registered = d.awaitPublished(PipelineEvents.DOCUMENT_REGISTERED, 2, PATIENCE);
            JsonNode second = InMemoryDomain.json(registered.get(1));
            assertThat(registered.get(0).getId()).isNotEqualTo(registered.get(1).getId());
            assertThat(second.path("data").path("version").asInt()).isEqualTo(2);
            assertThat(second.path("data").path("supersedes").asInt()).isEqualTo(1);
            assertThat(fixture.of("publish-page")).as("the page is published again, by the same key").hasSize(2)
                    .allSatisfy(c -> assertThat(c.input().path("key").asText()).isEqualTo("p1-c1-design"));

            List<CloudEvent> published = d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, PATIENCE);
            assertThat(published).hasSize(1);
            assertThat(InMemoryDomain.json(published.getFirst()).path("data").path("version").asInt()).isEqualTo(2);
            BusinessDesignWorkflowTest.assertContractAndNoMarker(d);
        }
    }

    @Test
    void three_rejections_end_the_design_without_publishing_it() throws Exception {
        try (InMemoryDomain d = domain()) {
            InstanceSnapshot end = d.run(BusinessDesignWorkflowTest.recorded(d, P1), r ->
                    BusinessDesignWorkflowTest.answer(r, "business-rep",
                            Json.obj().put("verdict", "rejected").put("noteRef", NOTE)), PATIENCE);
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(end.position()).endsWith("/rejected");
            assertThat(fixture.of("write-design")).hasSize(3);
            assertThat(fixture.of("register-document")).hasSize(3);
            assertThat(d.published(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();
            assertThat(fixture.of("write-timeout-report")).isEmpty();
        }
    }
}
