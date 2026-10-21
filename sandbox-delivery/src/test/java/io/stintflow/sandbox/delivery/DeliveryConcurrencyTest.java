package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.10, CA6 and 8f: the SDDs of a batch are implemented at the same time by the worker pool. The implementer of the
 * test holds each call at a barrier of three and lets them go together: if the three SDDs of the batch were not in
 * progress at once the barrier would never fill and the test would fail at its deadline. No sleep.
 */
class DeliveryConcurrencyTest {

    @Test
    void the_three_sdds_of_a_batch_are_being_implemented_at_the_same_time() throws Exception {
        var fx = new DeliveryFixture();
        List<String> ids = List.of("p5-sdd-1", "p5-sdd-2", "p5-sdd-3");
        ids.forEach(id -> fx.failUntil.put(id, 0));
        AtomicInteger together = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(3, together::incrementAndGet);
        try (InMemoryDomain d = new InMemoryDomain("delivery", DeliveryWorkflowsTest.YAMLS, (blobs, state) -> {
            TaskHandler inner = fx.implement(blobs);
            return fx.handlers(blobs, state).register("implement", ctx -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "the three SDDs were not in progress together", e));
                }
                return inner.execute(ctx);
            });
        })) {
            d.run(DeliveryFixture.batch(d, "p5", "1", ids), r -> null, Duration.ofSeconds(1));
            JsonNode closed = DeliveryWorkflowsTest.awaitClosed(d);

            assertThat(together.get()).as("the barrier filled once: the three were in progress together").isEqualTo(1);
            assertThat(closed.path("outcome").asText()).isEqualTo("complete");
            assertThat(closed.path("validated").asInt()).isEqualTo(3);
            assertThat(d.published(PipelineEvents.SDD_IMPLEMENTED)).hasSize(3);
        }
    }
}
