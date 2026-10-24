package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SDD S.14, CA3 (the channel): Business is started with its channel declared as not authenticated. It indexes and announces
 * as before, Engineering links its references, but Business does not answer the pointers: the resolution ends in an error
 * (the task is refused, 403) and nothing is resolved. An origin that does not say its channel is authenticated does not
 * answer, whatever the contract says. The declaration is a configuration, not a proof (finding S14-1).
 */
class BridgeAttestationIT extends PipelineScenario {

    @Test
    void without_the_attestation_of_the_channel_business_does_not_answer_the_pointers() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.bridge.channel-authenticated", "false")));
        new KnowledgePlant(floci).register("rec-at-1", 1, "The limit is three books per member. A late return blocks new"
                + " loans.", "at");
        spy.until("the references linked", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.engineering.references-linked.v1")), Duration.ofMinutes(3));
        spy.until("the resolution ended in an error", () -> failedResolutions() >= 1, Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(5));

        assertThat(spy.events().stream().filter(e -> e.type().equals("io.sandbox.engineering.pointers-resolved.v1")))
                .as("nothing was resolved").isEmpty();
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.nodes-published.v1")))
                .as("Business announced as before").isNotEmpty();
        assertThat(new Sweeper(corpus(), floci, mail, Module.CHANNELS.port()).stores()).isEmpty();
        Scenario.evidence("S.14-CA3-attestation.txt", "S.14 CA3: Business with its channel declared as not authenticated\n"
                + timeline() + "  announced and linked as before; resolutions failed: " + failedResolutions()
                + "; resolved: none\n");
    }

    private long failedResolutions() {
        return floci.ddb.scan(r -> r.tableName("engineering-instances")).items().stream()
                .filter(i -> "FAILED".equals(text(i.get("status"))) && text(i.get("definition")).contains("architecture-resolve"))
                .count();
    }

    private static String text(AttributeValue v) {
        return v == null || v.s() == null ? "" : v.s();
    }
}
