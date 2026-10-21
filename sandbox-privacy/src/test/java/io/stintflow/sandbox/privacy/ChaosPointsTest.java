package io.stintflow.sandbox.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.privacy.chaos.ChaosPoints;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;

/**
 * SDD S.11, 8b: the chaos points of the privacy domain. Off (the default), its worker gets exactly its own
 * handlers. On, the task handler halts once, after the effect of its route's first task and before its result.
 * The halt here is recorded, not real.
 */
class ChaosPointsTest {

    private final List<String> halts = new ArrayList<>();
    private final ChaosPoints.Halt recordHalt = halts::add;

    @Test
    void unset_chaos_properties_leave_the_handlers_untouched() {
        TaskHandlerRegistry registry = new TaskHandlerRegistry().register("probe", ctx -> done());
        TaskHandler probe = registry.find("probe").orElseThrow();

        assertThat(ChaosPoints.registry(registry, Optional.empty(), recordHalt)).isSameAs(registry);
        assertThat(ChaosPoints.registry(registry, Optional.of(" "), recordHalt)).isSameAs(registry);
        assertThat(registry.find("probe")).containsSame(probe);
        assertThat(halts).isEmpty();
    }

    @Test
    void the_task_handler_halts_once_after_its_effect_and_before_its_result() throws Exception {
        List<String> effects = new ArrayList<>();
        TaskHandlerRegistry registry = new TaskHandlerRegistry().register("probe", ctx -> {
            effects.add("did " + ctx.instanceId());
            return done();
        }).register("other", ctx -> done());
        TaskHandler other = registry.find("other").orElseThrow();

        ChaosPoints.registry(registry, Optional.of("probe"), recordHalt);
        TaskHandler probe = registry.find("probe").orElseThrow();

        probe.execute(task("i-1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(effects).containsExactly("did i-1");
        assertThat(halts).singleElement().asString().contains("task probe of instance i-1")
                .contains("after its effect, before its result");

        probe.execute(task("i-2")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(halts).hasSize(1);
        assertThat(registry.find("other")).containsSame(other);
        assertThatThrownBy(() -> ChaosPoints.registry(registry, Optional.of("no-such-route"), recordHalt))
                .hasMessageContaining("no-such-route");
    }

    private static CompletionStage<JsonNode> done() {
        return CompletableFuture.completedFuture(Json.obj().put("ok", true));
    }

    private static TaskContext task(String instanceId) {
        return new TaskContext("/do/3/probe", instanceId, "c-" + instanceId, 1, Json.obj(),
                new WorkflowRef("privacy", "probe", "1.0.0"), "chn-1", Lineage.NONE);
    }
}
