package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.11, RF6 (CA6, 8e): project p1's cycle with the domains that built as native images run as images (in containers
 * on the compose network) and the others on the JVM. The images are the ones {@code infra/gate.sh --native} built; the
 * modules to run natively come from {@code -Dscenarios.native.modules}. Skipped when none is given.
 */
class PipelineNativeIT extends PipelineScenario {

    @Test
    void p1_goes_through_its_cycle_with_the_native_domains_as_images() throws Exception {
        String listed = System.getProperty("scenarios.native.modules", "");
        assumeTrue(!listed.isBlank() || Scenario.nativeApps(), "native images only (-Dscenarios.native.modules=...)");
        Map<Module, App> running = startPipeline(Map.of());
        Map<String, String> kind = new LinkedHashMap<>();
        for (Map.Entry<Module, App> e : running.entrySet()) {
            String started = e.getValue().awaitLine(l -> l.contains("started in"), Duration.ofSeconds(1));
            boolean natively = started.contains("native");
            kind.put("sandbox-" + e.getKey().name().toLowerCase(), natively ? "native" : "jvm");
            note("%s: %s", e.getValue().name(), message(started));
        }
        for (Map.Entry<String, String> e : kind.entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(Scenario.nativeModule(e.getKey()) ? "native" : "jvm");
        }

        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        awaitClosed("p1", "1");
        spy.settle(Duration.ofSeconds(3));
        List<Spy.Event> ofP1 = eventsOf("p1");
        assertTheCountsOfACompleteCycle(ofP1, "p1");
        String closing = verify(List.of(PipelineEvents.chainIdOf("p1")), true);
        Scenario.evidence("S.11-CA6-run.txt", "S.11 RF6: p1's cycle with these domains as native images: " + kind + "\n"
                + timeline() + busOf("p1", ofP1) + closing);
    }
}
