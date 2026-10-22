package io.stintflow.sandbox.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
import io.stintflow.sandbox.billing.chaos.Chaos;
import io.stintflow.sandbox.billing.chaos.ChaosEventPublisher;
import io.stintflow.spi.AdapterCapabilities;
import io.stintflow.spi.EventPublisher;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.4, 8b: the chaos points. Off (the default), the engine and the worker get exactly their own publisher and
 * handlers. On, each halts once, at its point: the publisher before the first publication of its type, the task
 * handler after the effect of its route's first task and before its result. The halt here is recorded, not real.
 */
class ChaosPointsTest {

    private static final String ISSUED = "io.sandbox.billing.invoice-issued.v1";

    private final List<String> halts = new ArrayList<>();
    private final Chaos recordHalt = halts::add;

    @Test
    void unset_chaos_flags_leave_the_publisher_and_the_handlers_untouched() {
        RecordingPublisher publisher = new RecordingPublisher();
        TaskHandlerRegistry registry = new TaskHandlerRegistry().register(Billing.ROUTE_RENDER_INVOICE, ctx -> done());
        TaskHandler render = registry.find(Billing.ROUTE_RENDER_INVOICE).orElseThrow();

        assertThat(BillingEngine.publisher(publisher, Optional.empty(), recordHalt)).isSameAs(publisher);
        assertThat(BillingEngine.registry(registry, Optional.empty(), recordHalt)).isSameAs(registry);
        assertThat(registry.find(Billing.ROUTE_RENDER_INVOICE)).containsSame(render);
        assertThat(halts).isEmpty();
    }

    @Test
    void the_publisher_halts_once_before_the_first_publication_of_its_type() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        EventPublisher chaos = BillingEngine.publisher(publisher, Optional.of(ISSUED), recordHalt);
        assertThat(chaos).isInstanceOf(ChaosEventPublisher.class);

        chaos.publish(event("other", "io.sandbox.billing.invoice-rejected.v1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(halts).isEmpty();
        assertThat(publisher.published).containsExactly("other");

        // A real halt never returns; recorded here, the order shows it comes before the broker sees the event.
        chaos.publish(event("first", ISSUED));
        assertThat(halts).singleElement().asString().contains("publish of " + ISSUED + " id=first");
        assertThat(publisher.published).containsExactly("other", "first");

        chaos.publish(event("second", ISSUED));
        assertThat(halts).hasSize(1);
        assertThat(chaos.capabilities()).isSameAs(publisher.capabilities());
    }

    @Test
    void the_task_handler_halts_once_after_its_effect_and_before_its_result() throws Exception {
        List<String> effects = new ArrayList<>();
        TaskHandlerRegistry registry = new TaskHandlerRegistry()
                .register(Billing.ROUTE_RENDER_INVOICE, ctx -> {
                    effects.add("rendered " + ctx.instanceId());
                    return done();
                })
                .register(Billing.ROUTE_RECORD_INVOICE, ctx -> done());
        TaskHandler record = registry.find(Billing.ROUTE_RECORD_INVOICE).orElseThrow();

        BillingEngine.registry(registry, Optional.of(Billing.ROUTE_RENDER_INVOICE), recordHalt);
        TaskHandler render = registry.find(Billing.ROUTE_RENDER_INVOICE).orElseThrow();

        render.execute(task("i-1")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(effects).containsExactly("rendered i-1");
        assertThat(halts).singleElement().asString()
                .contains("task " + Billing.ROUTE_RENDER_INVOICE + " of instance i-1")
                .contains("after its effect, before its result");

        render.execute(task("i-2")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(halts).hasSize(1);
        assertThat(registry.find(Billing.ROUTE_RECORD_INVOICE)).containsSame(record);
        assertThatThrownBy(() -> BillingEngine.registry(registry, Optional.of("no-such-route"), recordHalt))
                .hasMessageContaining("no-such-route");
    }

    private static CompletionStage<JsonNode> done() {
        return CompletableFuture.completedFuture(Json.obj().put("ok", true));
    }

    private static TaskContext task(String instanceId) {
        return new TaskContext("/do/3/render", instanceId, "c-" + instanceId, 1, Json.obj(), Billing.ISSUE_INVOICE);
    }

    private static CloudEvent event(String id, String type) {
        return CloudEventBuilder.v1().withId(id).withType(type).withSource(URI.create("https://sandbox.stintflow.io/billing"))
                .build();
    }

    private static final class RecordingPublisher implements EventPublisher {
        final List<String> published = new ArrayList<>();
        final AdapterCapabilities capabilities = new AdapterCapabilities(
                AdapterCapabilities.DeliveryGuarantee.AT_LEAST_ONCE, false, 262144, null, false, false);

        @Override
        public CompletionStage<Void> publish(CloudEvent event) {
            published.add(event.getId());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public AdapterCapabilities capabilities() {
            return capabilities;
        }
    }
}
