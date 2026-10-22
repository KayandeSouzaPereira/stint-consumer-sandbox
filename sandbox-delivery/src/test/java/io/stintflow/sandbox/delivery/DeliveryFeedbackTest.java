package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.10, CA1, CA3, CA4 and CA7: what reaches the next attempt (the validator's report, the criteria that failed,
 * the technician's note — all by pointer), the DLQ's history of the attempts, the resend that reopens only its SDD,
 * and every attempt's patch and report in the register with the event published.
 */
class DeliveryFeedbackTest {

    static final List<String> YAMLS = DeliveryWorkflowsTest.YAMLS;

    static CloudEvent decide(CloudEvent request, String value, String noteRef) {
        var data = Json.obj().put("value", value).put("reviewer", "rev-test");
        if (noteRef != null) {
            data.put("noteRef", noteRef);
        }
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create(PipelineEvents.SOURCE_PORTAL)).withType(StintEvents.TYPE_REVIEW_COMPLETED)
                .withSubject(request.getSubject()).withData(Json.bytes(data))
                .withExtension(StintEvents.EXT_ROLE, "technician").build();
    }

    static CloudEvent awaitRequest(InMemoryDomain d) {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<CloudEvent> requests = List.of();
        while (requests.isEmpty() && System.nanoTime() < until) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            requests = d.published(PipelineEvents.REVIEW_REQUESTED);
        }
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    static List<JsonNode> awaitImplementations(DeliveryFixture fx, String sddId, int count) {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<JsonNode> seen = fx.implementationsOf(sddId);
        while (seen.size() < count && System.nanoTime() < until) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            seen = fx.implementationsOf(sddId);
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(count);
        return seen;
    }

    @Test
    void the_next_attempt_gets_the_report_and_the_failed_criteria_of_the_one_before_by_pointer() throws Exception {
        var fx = new DeliveryFixture();
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p1", "1", List.of("p1-sdd-1", "p1-sdd-2")), r -> null,
                    Duration.ofSeconds(1));
            DeliveryWorkflowsTest.awaitClosed(d);

            // p1-sdd-1 passes at once: nothing to feed back; p1-sdd-2 fails the tests of its first attempt
            assertThat(fx.implementationsOf("p1-sdd-1")).singleElement().satisfies(first -> {
                assertThat(first.hasNonNull("feedbackRef")).isFalse();
                assertThat(first.path("failedCriteria")).isEmpty();
            });
            List<JsonNode> two = fx.implementationsOf("p1-sdd-2");
            assertThat(two).hasSize(2);
            assertThat(two.get(0).hasNonNull("feedbackRef")).isFalse();
            assertThat(two.get(1).path("feedbackRef").asText()).isEqualTo("s3://sandbox-test/reports/p1-sdd-2/1.json");
            assertThat(two.get(1).path("failedCriteria")).extracting(JsonNode::asText).containsExactly("tests");
            assertThat(two.get(1).hasNonNull("noteRef")).isFalse();
            // a pointer and ids: the input carries no text of the report, and the report names what failed
            assertThat(two.get(1).toString()).doesNotContain("FAILED").doesNotContain("Ran 5");
            JsonNode report = Json.read(d.blobs.data.get(URI.create(two.get(1).path("feedbackRef").asText())));
            assertThat(report.path("tests").path("passed").asBoolean()).isFalse();
            assertThat(report.path("coverage").path("passed").asBoolean()).isTrue();
            assertThat(report.path("conformance").path("passed").asBoolean()).isTrue();
        }
    }

    @Test
    void a_criterion_the_tests_do_not_cite_is_what_the_next_attempt_is_told() throws Exception {
        var fx = new DeliveryFixture();
        fx.cites.put("p6-sdd-1", List.of("CA1"));          // the tests pass; CA2 of the SDD is never cited
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p6", "1", List.of("p6-sdd-1")), r -> null, Duration.ofSeconds(1));

            List<JsonNode> attempts = awaitImplementations(fx, "p6-sdd-1", 2);
            assertThat(attempts.get(1).path("failedCriteria")).extracting(JsonNode::asText).containsExactly("CA2");
            JsonNode report = Json.read(d.blobs.data.get(URI.create(attempts.get(1).path("feedbackRef").asText())));
            assertThat(report.path("tests").path("passed").asBoolean()).isTrue();
            assertThat(report.path("coverage").path("failed")).extracting(JsonNode::asText).containsExactly("CA2");
            assertThat(report.path("validated").asBoolean()).isFalse();
        }
    }

    @Test
    void an_sdd_out_of_attempts_goes_to_the_dlq_with_the_history_of_its_five_attempts() throws Exception {
        var fx = new DeliveryFixture();
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p9", "1", List.of("dlq-sdd")), r -> null, Duration.ofSeconds(1));
            CloudEvent request = awaitRequest(d);

            CloudEvent deadLettered = d.awaitPublished(PipelineEvents.SDD_DEAD_LETTERED, 1, Duration.ofSeconds(10))
                    .getFirst();
            JsonNode data = Json.read(deadLettered.getData().toBytes());
            assertThat(data.path("attempts").asInt()).isEqualTo(5);
            assertThat(data.path("history")).hasSize(5);
            for (int i = 0; i < 5; i++) {
                JsonNode item = data.path("history").get(i);
                assertThat(item.path("attempt").asInt()).isEqualTo(i + 1);
                assertThat(item.path("patchRef").asText()).isEqualTo("s3://sandbox-test/changesets/dlq-sdd/" + (i + 1) + ".json");
                assertThat(item.path("reportRef").asText()).isEqualTo("s3://sandbox-test/reports/dlq-sdd/" + (i + 1) + ".json");
            }
            assertThat(data.path("lastReportRef").asText()).isEqualTo("s3://sandbox-test/reports/dlq-sdd/5.json");
            DeliveryWorkflowsTest.assertContract(d);
            // while it waits for the technician the SDD is parked (the daily summary lists it)
            assertThat(fx.parked.all()).singleElement().satisfies(p -> {
                assertThat(p.sddId()).isEqualTo("dlq-sdd");
                assertThat(p.attempts()).isEqualTo(5);
            });

            d.engine.onDomainEvent(decide(request, "cancel", null), Duration.ofMinutes(5)).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            DeliveryWorkflowsTest.awaitClosed(d);
            assertThat(fx.parked.all()).isEmpty();
        }
    }

    @Test
    void a_resend_with_a_note_reopens_only_that_sdd_and_the_note_goes_by_pointer() throws Exception {
        var fx = new DeliveryFixture();
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p9", "1", List.of("p4-sdd-1", "dlq-sdd")), r -> null,
                    Duration.ofSeconds(1));
            CloudEvent request = awaitRequest(d);

            d.engine.onDomainEvent(decide(request, "resend", DeliveryFixture.NOTE), Duration.ofMinutes(5))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            JsonNode closed = DeliveryWorkflowsTest.awaitClosed(d);

            // five more attempts were granted; the sixth passes (the stand-in fails five) and carries the note
            List<JsonNode> dlq = fx.implementationsOf("dlq-sdd");
            assertThat(dlq).hasSize(6);
            assertThat(dlq.get(5).path("attempt").asInt()).isEqualTo(6);
            assertThat(dlq.get(5).path("noteRef").asText()).isEqualTo(DeliveryFixture.NOTE);
            assertThat(dlq.get(5).path("feedbackRef").asText()).isEqualTo("s3://sandbox-test/reports/dlq-sdd/5.json");
            assertThat(dlq.get(4).hasNonNull("noteRef")).isFalse();
            // the other SDD was not touched
            assertThat(fx.implementationsOf("p4-sdd-1")).hasSize(1);
            assertThat(closed.path("outcome").asText()).isEqualTo("complete");
            assertThat(closed.path("validated").asInt()).isEqualTo(2);
            assertThat(d.published(PipelineEvents.SDD_VALIDATED)).filteredOn(e -> "dlq-sdd".equals(
                    e.getExtension(PipelineEvents.EXT_SDD))).singleElement()
                    .matches(e -> Json.read(e.getData().toBytes()).path("attempts").asInt() == 6);
            assertThat(fx.parked.all()).isEmpty();
        }
    }

    @Test
    void accepting_the_sdd_closes_the_cycle_as_complete() throws Exception {
        var fx = new DeliveryFixture();
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p9", "1", List.of("p4-sdd-1", "dlq-sdd")), r -> null,
                    Duration.ofSeconds(1));
            CloudEvent request = awaitRequest(d);

            d.engine.onDomainEvent(decide(request, "accept", null), Duration.ofMinutes(5)).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            JsonNode closed = DeliveryWorkflowsTest.awaitClosed(d);

            assertThat(closed.path("outcome").asText()).isEqualTo("complete");
            assertThat(closed.path("validated").asInt()).isEqualTo(1);
            assertThat(closed.path("accepted").asInt()).isEqualTo(1);
            assertThat(fx.implementationsOf("dlq-sdd")).hasSize(5);
            assertThat(fx.parked.all()).isEmpty();
        }
    }

    @Test
    void every_attempts_patch_and_report_are_registered_and_published() throws Exception {
        var fx = new DeliveryFixture();
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, fx::handlers)) {
            d.run(DeliveryFixture.batch(d, "p1", "1", List.of("p1-sdd-1", "p1-sdd-2")), r -> null,
                    Duration.ofSeconds(1));
            DeliveryWorkflowsTest.awaitClosed(d);

            // two SDDs, one with two attempts: three patches and three reports, as the register would hold them
            assertThat(fx.registeredKinds()).containsOnly(
                    java.util.Map.entry("p1-sdd-1-a1-patch", "patch"), java.util.Map.entry("p1-sdd-1-a1-report", "report"),
                    java.util.Map.entry("p1-sdd-2-a1-patch", "patch"), java.util.Map.entry("p1-sdd-2-a1-report", "report"),
                    java.util.Map.entry("p1-sdd-2-a2-patch", "patch"), java.util.Map.entry("p1-sdd-2-a2-report", "report"));
            assertThat(fx.registered).allSatisfy(r -> {
                assertThat(r.path("ownerRole").asText()).isEqualTo("technician");
                assertThat(r.path("projectId").asText()).isEqualTo("p1");
            });
            List<CloudEvent> events = d.awaitPublished(PipelineEvents.DOCUMENT_REGISTERED, 6, Duration.ofSeconds(10));
            assertThat(events).hasSize(6);
            assertThat(events).extracting(e -> Json.read(e.getData().toBytes()).path("kind").asText())
                    .containsExactlyInAnyOrder("patch", "patch", "patch", "report", "report", "report");
            DeliveryWorkflowsTest.assertContract(d);
        }
    }
}
