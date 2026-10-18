package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.5, 8a (A): the coordinator and one instance per SDD, on one in-memory engine with the bus looped back (as
 * sandbox-domain routes Delivery's own events to it). Two SDDs run as two instances; the second fails its first
 * attempt and passes the second; the coordinator closes the cycle once both ended. An SDD out of attempts goes to the
 * technician, whose choice ends it; a cancelled SDD makes the cycle partial. Events keep the v1 contract.
 */
class DeliveryWorkflowsTest {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));
    static final PipelineContracts CONTRACTS = new PipelineContracts();
    static final List<String> YAMLS = List.of("delivery-cycle.yaml", "sdd-delivery.yaml");

    /** The domain's routes; the target's tests stand in: a changeset with the failing mark fails. */
    static TaskHandlerRegistry handlers(BlobStore blobs) {
        return DeliveryHandlers.register(new TaskHandlerRegistry(), CORPUS, blobs)
                .register("run-target-tests", ctx -> blobs.get(URI.create(ctx.input().path("changesetRef").asText()))
                        .thenCompose(bytes -> {
                            boolean failing = Json.read(bytes).toString().contains(DeliveryHandlers.FAILING);
                            String key = "runs/" + ctx.input().path("sddId").asText() + "/"
                                    + ctx.input().path("attempt").asInt() + ".txt";
                            return blobs.put(("Ran 5 tests\n" + (failing ? "FAILED (failures=1)" : "OK")).getBytes(),
                                    key).thenApply(uri -> (JsonNode) Json.obj().put("outcome",
                                            failing ? "failed" : "passed").put("passed", !failing)
                                            .put("reportRef", uri.toString()));
                        }));
    }

    /** As {@link #handlers}, with an implementer that always fails the SDDs whose id starts with {@code dlq-}. */
    static TaskHandlerRegistry alwaysFailing(BlobStore blobs) {
        return handlers(blobs).register("implement", ctx -> {
            String sddId = ctx.input().path("sddId").asText();
            boolean failing = sddId.startsWith("dlq-") || CORPUS.sdd(sddId).map(Corpus.Sdd::failures).orElse(0)
                    >= ctx.input().path("attempt").asInt();
            return blobs.put(Json.bytes(DeliveryHandlers.changeset(sddId, failing)), "changesets/" + sddId + "/"
                    + ctx.input().path("attempt").asInt() + ".json")
                    .thenApply(uri -> (JsonNode) Json.obj().put("changesetRef", uri.toString()));
        });
    }

    /** Engineering's batch: the manifest in the blob store, and the approved event. */
    static CloudEvent batch(InMemoryDomain d, String project, String cycle, List<String> sddIds) throws Exception {
        ObjectNode manifest = Json.obj().put("designRecordId", "rec-" + project + "-c" + cycle + "-design")
                .put("techRecordId", "rec-" + project + "-c" + cycle + "-batch");
        ArrayNode sdds = manifest.putArray("sdds");
        for (String id : sddIds) {
            URI ref = d.blobs.put(("# SDD " + id).getBytes(), "sdd/" + id + ".md").toCompletableFuture().join();
            sdds.addObject().put("sddId", id).put("sddRef", ref.toString()).put("recordId", "rec-" + id);
        }
        URI batchRef = d.blobs.put(Json.bytes(manifest), "sdd/" + project + "-c" + cycle + "-batch.json")
                .toCompletableFuture().join();
        ObjectNode data = Json.obj().put("batchId", project + "-c" + cycle + "-batch")
                .put("batchRef", batchRef.toString());
        sddIds.forEach(data.putArray("sddIds")::add);
        data.putArray("recordIds");
        return CloudEventBuilder.v1().withId(project + ":" + cycle + ":sdd-batch-approved")
                .withSource(URI.create(PipelineEvents.SOURCE_ENGINEERING)).withType(PipelineEvents.SDD_BATCH_APPROVED)
                .withSubject(project + "-c" + cycle + "-batch").withData(Json.bytes(data))
                .withExtension(PipelineEvents.EXT_PROJECT, project).withExtension(PipelineEvents.EXT_CYCLE, cycle)
                .withExtension(StintEvents.EXT_CHAIN_ID, PipelineEvents.chainIdOf(project))
                .withExtension(StintEvents.EXT_SENSITIVITY, "confidential").build();
    }

    static CloudEvent choose(CloudEvent request, String value) {
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create(PipelineEvents.SOURCE_PORTAL)).withType(StintEvents.TYPE_REVIEW_COMPLETED)
                .withSubject(request.getSubject()).withData(Json.bytes(Json.obj().put("value", value)
                        .put("reviewer", "rev-test")))
                .withExtension(StintEvents.EXT_ROLE, "technician").build();
    }

    /** The cycle-closed event, once the outbox published it (the coordinator ends on the last SDD's event). */
    static JsonNode awaitClosed(InMemoryDomain d) {
        List<CloudEvent> closed = d.awaitPublished(PipelineEvents.CYCLE_CLOSED, 1, Duration.ofSeconds(30));
        assertThat(closed).hasSize(1);
        return Json.read(closed.getFirst().getData().toBytes());
    }

    static void assertContract(InMemoryDomain d) {
        for (CloudEvent e : d.bus.published()) {
            if (CONTRACTS.isPipelineType(e.getType())) {
                assertThat(CONTRACTS.violations(InMemoryDomain.json(e))).as(e.getType()).isEmpty();
            }
        }
    }

    @Test
    void two_sdds_run_as_two_instances_and_the_cycle_closes_once_both_ended() throws Exception {
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, DeliveryWorkflowsTest::handlers)) {
            d.run(batch(d, "p1", "1", List.of("p1-sdd-1", "p1-sdd-2")), r -> null, Duration.ofSeconds(1));

            JsonNode closed = awaitClosed(d);
            assertThat(closed.path("outcome").asText()).isEqualTo("complete");
            assertThat(closed.path("validated").asInt()).isEqualTo(2);
            assertThat(d.published(PipelineEvents.SDD_READY)).hasSize(2);
            assertThat(d.published(PipelineEvents.SDD_IMPLEMENTED)).hasSize(3);   // p1-sdd-2 needed two attempts
            List<CloudEvent> validated = d.published(PipelineEvents.SDD_VALIDATED);
            assertThat(validated).extracting(e -> e.getExtension(PipelineEvents.EXT_SDD))
                    .containsExactlyInAnyOrder("p1-sdd-1", "p1-sdd-2");
            assertThat(validated).filteredOn(e -> "p1-sdd-2".equals(e.getExtension(PipelineEvents.EXT_SDD)))
                    .singleElement().matches(e -> Json.read(e.getData().toBytes()).path("attempts").asInt() == 2);
            assertThat(d.bus.published()).allMatch(e -> PipelineEvents.chainIdOf("p1")
                    .equals(e.getExtension(StintEvents.EXT_CHAIN_ID)));
            assertContract(d);
        }
    }

    @Test
    void an_sdd_out_of_attempts_goes_to_the_technician_and_a_cancel_makes_the_cycle_partial() throws Exception {
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, DeliveryWorkflowsTest::alwaysFailing)) {
            d.run(batch(d, "p9", "1", List.of("p4-sdd-1", "dlq-sdd")), r -> null, Duration.ofSeconds(1));
            List<CloudEvent> requests = List.of();
            long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (requests.isEmpty() && System.nanoTime() < until) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                requests = d.published(PipelineEvents.REVIEW_REQUESTED);
            }
            assertThat(requests).singleElement()
                    .matches(e -> "technician".equals(e.getExtension(StintEvents.EXT_ROLE)))
                    .matches(e -> "delivery".equals(e.getExtension(PipelineEvents.EXT_DOMAIN)));
            assertThat(d.published(PipelineEvents.SDD_DEAD_LETTERED)).singleElement()
                    .matches(e -> Json.read(e.getData().toBytes()).path("attempts").asInt() == 5);

            d.engine.onDomainEvent(choose(requests.getFirst(), "cancel"), Duration.ofMinutes(5)).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            JsonNode closed = awaitClosed(d);
            assertThat(closed.path("outcome").asText()).isEqualTo("partial");
            assertThat(closed.path("validated").asInt()).isEqualTo(1);
            assertThat(closed.path("cancelled").asInt()).isEqualTo(1);
            assertThat(d.published(PipelineEvents.SDD_IMPLEMENTED)).hasSize(6);
            assertContract(d);
        }
    }

    @Test
    void a_redelivered_batch_starts_nothing_twice() throws Exception {
        try (InMemoryDomain d = new InMemoryDomain("delivery", YAMLS, DeliveryWorkflowsTest::handlers)) {
            CloudEvent event = batch(d, "p5", "1", List.of("p5-sdd-1"));
            InstanceSnapshot first = d.run(event, r -> null, Duration.ofSeconds(1));
            String again = d.engine.startFromEvent(d.definition.ref(), event).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(again).isEqualTo(first.instanceId());
            awaitClosed(d);
            assertThat(d.published(PipelineEvents.SDD_READY)).hasSize(1);
            assertThat(d.published(PipelineEvents.SDD_VALIDATED)).hasSize(1);
        }
    }
}
