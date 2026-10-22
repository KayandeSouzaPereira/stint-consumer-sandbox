package io.stintflow.sandbox.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.wire.Json;

/**
 * SDD S.9, RF1/RF2/RF5 and CA1/CA2/CA5: every technical script of the corpus takes the path its answer key says — the
 * type, the outcome, the blocking question, the number of SDDs or of questions — and every SDD it writes has the
 * process's format and one criterion per thing the key says it must cover.
 */
class EngineeringCorpusTest {

    @TestFactory
    List<DynamicTest> every_technical_script_takes_the_path_of_its_key() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Corpus.Meeting m : EngineeringFixture.CORPUS.meetings()) {
            if ("technical".equals(m.stage())) {
                tests.add(DynamicTest.dynamicTest(m.id(), () -> follow(m)));
            }
        }
        assertThat(tests).as("the corpus has the seven technical scripts").hasSize(7);
        return tests;
    }

    void follow(Corpus.Meeting m) throws Exception {
        EngineeringFixture fixture = new EngineeringFixture();
        try (InMemoryDomain d = new InMemoryDomain("engineering", "technical-meeting.yaml", fixture::handlers)) {
            InstanceSnapshot end = d.run(EngineeringWorkflowsTest.recorded(d, m),
                    r -> EngineeringWorkflowsTest.asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            EngineeringWorkflowsTest.assertContractAndNoMarker(d);

            Corpus.Expected key = m.expected();
            if (!"technical-refinement".equals(key.meetingType())) {
                assertThat(fixture.of("register-document")).as("out of scope: nothing registered").isEmpty();
                assertThat(d.published(PipelineEvents.SDD_BATCH_APPROVED)).isEmpty();
                assertThat(d.published(PipelineEvents.QUESTIONS_SENT)).isEmpty();
                return;
            }
            boolean asks = "questions".equals(key.outcome()) || Boolean.TRUE.equals(key.blockingQuestion());
            if (asks) {
                assertThat(d.awaitPublished(PipelineEvents.QUESTIONS_SENT, 1, Duration.ofSeconds(5))).hasSize(1);
                JsonNode sent = InMemoryDomain.json(d.published(PipelineEvents.QUESTIONS_SENT).getFirst()).path("data");
                assertThat(sent.path("nextCycle").asInt()).isEqualTo(m.cycle() + 1);
                if (key.questions() != null && !key.questions().isEmpty()) {
                    assertThat(sent.path("questionCount").asInt()).isEqualTo(key.questions().size());
                }
                assertThat(fixture.registered("questions")).hasSize(1);
                assertThat(fixture.registered("sdd")).isEmpty();
                assertThat(d.published(PipelineEvents.SDD_BATCH_APPROVED)).isEmpty();
                return;
            }
            List<CloudEvent> batches = d.awaitPublished(PipelineEvents.SDD_BATCH_APPROVED, 1, Duration.ofSeconds(5));
            assertThat(batches).hasSize(1);
            JsonNode data = InMemoryDomain.json(batches.getFirst()).path("data");
            assertThat(data.path("sddIds")).extracting(JsonNode::asText)
                    .containsExactlyElementsOf(key.sdds().stream().map(Corpus.Sdd::id).toList());
            assertThat(data.path("sddIds")).hasSize(key.sddCount());
            assertThat(data.path("priority").asDouble(-1)).as("the priority travels with the batch")
                    .isBetween(key.priority() - 0.2, key.priority() + 0.2);
            assertThat(fixture.registered("sdd")).hasSize(key.sddCount());
            assertThat(fixture.registered("sdd-batch")).hasSize(1);
            assertThat(d.published(PipelineEvents.QUESTIONS_SENT)).isEmpty();

            for (Corpus.Sdd sdd : key.sdds()) {   // the process's format, one criterion per thing it must cover
                String text = textOf(d, fixture.registered("sdd").stream()
                        .filter(i -> sdd.id().equals(i.path("documentId").asText())).findFirst().orElseThrow());
                assertThat(SddFormat.violations(text, sdd.id())).as(sdd.id()).isEmpty();
                assertThat(SddFormat.criteriaCount(text)).as(sdd.id() + " criteria").isEqualTo(
                        Math.max(1, sdd.covers() == null ? 0 : sdd.covers().size()));
            }
        }
    }

    static String textOf(InMemoryDomain d, JsonNode registration) {
        byte[] bytes = d.blobs.get(URI.create(registration.path("documentRef").asText())).toCompletableFuture().join();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
