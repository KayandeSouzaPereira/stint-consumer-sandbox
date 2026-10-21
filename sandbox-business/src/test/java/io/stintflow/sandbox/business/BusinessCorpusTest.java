package io.stintflow.sandbox.business;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * SDD S.8, RF1/RF5 and CA1/CA4: every business script of the corpus takes the path its answer key says, with the CI's
 * models — the type, the priority (within the key's tolerance), the channel and the artifact that channel produces.
 * The ambiguous script goes to the business representative, who answers as the key does.
 */
class BusinessCorpusTest {

    static final double TOLERANCE = 0.2;

    @TestFactory
    List<DynamicTest> every_business_script_takes_the_path_of_its_key() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Corpus.Meeting m : BusinessFixture.CORPUS.meetings()) {
            if ("business".equals(m.stage())) {
                tests.add(DynamicTest.dynamicTest(m.id(), () -> follow(m)));
            }
        }
        assertThat(tests).as("the corpus has the seven business scripts").hasSize(7);
        return tests;
    }

    void follow(Corpus.Meeting m) throws Exception {
        BusinessFixture fixture = new BusinessFixture();
        try (InMemoryDomain d = new InMemoryDomain("business", "business-design.yaml", fixture::handlers)) {
            InstanceSnapshot end = d.run(BusinessDesignWorkflowTest.recorded(d, m),
                    r -> BusinessDesignWorkflowTest.asTheKey(r, m), Duration.ofSeconds(30));
            assertThat(end.status()).as(end.context().toString()).isEqualTo(InstanceStatus.COMPLETED);
            BusinessDesignWorkflowTest.assertContractAndNoMarker(d);

            Corpus.Expected key = m.expected();
            List<CloudEvent> published = d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 0, Duration.ofMillis(10));
            if (!"business-design".equals(key.meetingType())) {
                assertThat(fixture.of("register-document")).as("out of scope: no design").isEmpty();
                assertThat(fixture.calls).extracting(BusinessFixture.Call::route)
                        .doesNotContain("publish-page", "send-email");
                assertThat(published).isEmpty();
                return;
            }
            assertThat(fixture.of("register-document")).hasSize(1);
            String channel = key.channel();
            switch (channel) {
                case "confluence" -> {
                    assertThat(fixture.of("publish-page")).hasSize(1);
                    assertThat(fixture.of("publish-page").getFirst().input().path("key").asText())
                            .isEqualTo(m.project() + "-c" + m.cycle() + "-design");
                    assertThat(fixture.of("send-email")).isEmpty();
                }
                case "email" -> {
                    assertThat(fixture.of("send-email")).hasSize(1);
                    assertThat(fixture.of("send-email").getFirst().input().path("template").asText()).isEqualTo("design");
                    assertThat(fixture.of("publish-page")).isEmpty();
                }
                default -> {   // a new meeting is asked for: no approval, no design
                    assertThat(fixture.of("send-email")).hasSize(1);
                    assertThat(fixture.of("send-email").getFirst().input().path("template").asText())
                            .isEqualTo("meeting");
                    assertThat(fixture.of("publish-page")).isEmpty();
                    assertThat(d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, Duration.ofMillis(200))).isEmpty();
                    return;
                }
            }
            published = d.awaitPublished(PipelineEvents.DESIGN_PUBLISHED, 1, Duration.ofSeconds(5));
            assertThat(published).hasSize(1);
            JsonNode data = InMemoryDomain.json(published.getFirst()).path("data");
            assertThat(data.path("channel").asText()).isEqualTo(channel);
            assertThat(data.path("priority").asDouble(-1)).as("the priority travels with the design")
                    .isBetween(key.priority() - TOLERANCE, key.priority() + TOLERANCE);
        }
    }
}
