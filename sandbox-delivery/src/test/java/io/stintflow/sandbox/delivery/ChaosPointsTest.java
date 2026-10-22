package io.stintflow.sandbox.delivery;

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

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.spi.AdapterCapabilities;
import io.stintflow.spi.EventPublisher;
import io.stintflow.sandbox.delivery.chaos.ChaosPoints;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;

/**
 * SDD S.11, 8b: the chaos points of the delivery domain. Off (the default), its worker and engine gets exactly its own
 * handlers. On, the task handler halts once, after the effect of its route's first task and before its result, and the publisher before the first publication of its type.
 * The halt here is recorded, not real.
 */
class ChaosPointsTest {

    private final List<String> halts = new ArrayList<>();
    private final ChaosPoints.Halt recordHalt = halts::add;

    @Test
    void unset_chaos_properties_leave_the_handlers_and_the_publisher_untouched() {
        TaskHandlerRegistry registry = new TaskHandlerRegistry().register("probe", ctx -> done());
        TaskHandler probe = registry.find("probe").orElseThrow();

        assertThat(ChaosPoints.registry(registry, Optional.empty(), recordHalt)).isSameAs(registry);
        assertThat(ChaosPoints.registry(registry, Optional.of(" "), recordHalt)).isSameAs(registry);
        assertThat(registry.find("probe")).containsSame(probe);
        EventPublisher publisher = new EventPublisher() {
            @Override
            public CompletionStage<Void> publish(CloudEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public AdapterCapabilities capabilities() {
                return null;
            }
        };
        assertThat(ChaosPoints.publisher(publisher, Optional.empty(), recordHalt)).isSameAs(publisher);
        assertThat(ChaosPoints.publisher(publisher, Optional.of(""), recordHalt)).isSameAs(publisher);
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

    @Test
    void the_publisher_halts_once_before_the_first_publication_of_its_type() throws Exception {
        List<String> published = new ArrayList<>();
        AdapterCapabilities capabilities = new AdapterCapabilities(
                AdapterCapabilities.DeliveryGuarantee.AT_LEAST_ONCE, false, 262144, null, false, false);
        EventPublisher inner = new EventPublisher() {
            @Override
            public CompletionStage<Void> publish(CloudEvent event) {
                published.add(event.getId());
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public AdapterCapabilities capabilities() {
                return capabilities;
            }
        };
        assertThat(ChaosPoints.publisher(inner, Optional.empty(), recordHalt)).isSameAs(inner);
        EventPublisher chaos = ChaosPoints.publisher(inner, Optional.of("probe.type.v1"), recordHalt);
        assertThat(chaos).isNotSameAs(inner);

        chaos.publish(event("other", "other.type.v1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(halts).isEmpty();

        // a real halt never returns; recorded here, the order shows it comes before the broker sees the event
        chaos.publish(event("first", "probe.type.v1"));
        assertThat(halts).singleElement().asString().contains("publish of probe.type.v1 id=first");
        assertThat(published).containsExactly("other", "first");

        chaos.publish(event("second", "probe.type.v1"));
        assertThat(halts).hasSize(1);
        assertThat(chaos.capabilities()).isSameAs(capabilities);
    }

    private static CloudEvent event(String id, String type) {
        return CloudEventBuilder.v1().withId(id).withType(type)
                .withSource(java.net.URI.create("https://sandbox.stintflow.io/delivery")).build();
    }

    private static CompletionStage<JsonNode> done() {
        return CompletableFuture.completedFuture(Json.obj().put("ok", true));
    }

    private static TaskContext task(String instanceId) {
        return new TaskContext("/do/3/probe", instanceId, "c-" + instanceId, 1, Json.obj(),
                new WorkflowRef("delivery", "probe", "1.0.0"), "chn-1", Lineage.NONE);
    }
}
