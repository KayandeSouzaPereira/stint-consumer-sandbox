package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SDD S.14, CA7 (the trail): after p2's cycle the audit tables of the two domains tell what happened to the chain, step by
 * step — the announcement and the resolution among them — in ids, routes, kinds, levels and counts, and never in the words of
 * what Business decided. The sweeper reads the tables whole.
 */
class BridgeAuditIT extends PipelineScenario {

    @Test
    void the_trail_of_the_chain_shows_the_bridge_and_none_of_its_content() throws Exception {
        startPipeline(Map.of());
        portal.rejectItemOnce("p2-c2-batch", "p2-sdd-1");
        portal.rejectFirstApprovalOf("p2-c1-design");
        assertThat(portal.record("p2-c1-business")).isEqualTo(202);
        awaitClosed("p2", "2");
        spy.until("the pointers resolved", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.engineering.pointers-resolved.v1")), Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(8));

        String chain = PipelineEvents.chainIdOf("p2");
        Set<String> trail = new TreeSet<>();
        List<String> everything = new ArrayList<>();
        for (String domain : List.of("business", "engineering")) {
            for (Map<String, AttributeValue> item : floci.ddb.scan(r -> r.tableName(domain + "-audit")).items()) {
                everything.add(item.toString());
                if (chain.equals(text(item.get("chainId")))) {
                    trail.add(domain + ":" + text(item.get("route")) + ":" + text(item.get("kind")));
                }
            }
        }
        note("the trail: %s", trail);
        assertThat(trail).as("the announcement, the references and the resolution are on the chain's trail")
                .anySatisfy(t -> assertThat(t).startsWith("business:knowledge-announce"))
                .anySatisfy(t -> assertThat(t).startsWith("engineering:knowledge-reference"))
                .anySatisfy(t -> assertThat(t).startsWith("engineering:resolve-pointers"))
                .anySatisfy(t -> assertThat(t).contains(":pointer-resolve"));

        KnowledgeCorpus kb = KnowledgeCorpus.load(Scenario.root().resolve("corpus"));
        List<String> words = new ArrayList<>();
        kb.decisions().forEach(d -> {
            words.add(d.label());
            words.add(d.summary());
        });
        kb.requirements().forEach(r -> {
            words.add(r.label());
            words.add(r.summary());
        });
        for (String entry : everything) {
            for (String word : words) {
                assertThat(entry).as("the trail holds ids and counts, not the words of a decision").doesNotContain(word);
            }
        }
        assertThat(new Sweeper(corpus(), floci, mail, Module.CHANNELS.port()).texts("audit", everything)).isEmpty();
        String closing = verify(List.of(chain), true);
        Scenario.evidence("S.14-CA7.txt", "S.14 CA7: the trail of the chain across the two audit tables\n" + timeline()
                + "  steps on the trail (domain:route:kind): " + trail + "\n  audit entries read: " + everything.size() + "\n"
                + closing);
    }

    private static String text(AttributeValue v) {
        return v == null || v.s() == null ? "" : v.s();
    }
}
