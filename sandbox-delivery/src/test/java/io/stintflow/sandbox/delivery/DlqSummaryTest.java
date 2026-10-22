package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.10, CA5: the daily summary of the DLQ. It is a cron schedule of the domain; an occurrence lists the SDDs parked
 * for the technician and mails them once; with none parked nothing is written and nothing is mailed. The mail's body is
 * the summary the pointer names (ids and attempts), never a text of a document.
 */
class DlqSummaryTest {

    static final Instant T = Instant.parse("2026-10-21T09:00:00Z");

    final ParkedSdds.InMemory parked = new ParkedSdds.InMemory();
    final List<JsonNode> mails = Collections.synchronizedList(new ArrayList<>());

    TaskHandlerRegistry handlers(io.stintflow.spi.BlobStore blobs, io.stintflow.spi.StateStore state) {
        return new TaskHandlerRegistry().register("summarize-dlq", DeliveryHandlers.summarizeDlq(parked, blobs))
                .register("send-email", ctx -> {
                    mails.add(ctx.input().deepCopy());
                    return CompletableFuture.completedFuture(Json.obj().put("sent", true));
                });
    }

    /** The synthetic event a cron occurrence starts its instance with (as Stint's ScheduledStart builds it). */
    static CloudEvent occurrence(InMemoryDomain d, Instant at) {
        return CloudEventBuilder.v1().withId("dlq-summary@" + at)
                .withSource(URI.create("stint:schedule/" + d.definition.ref().canonical()))
                .withType(StintEvents.TYPE_SCHEDULE_FIRED).withTime(at.atOffset(ZoneOffset.UTC)).build();
    }

    @Test
    void the_domain_declares_the_summary_as_a_daily_cron() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("stint/workflows/dlq-summary.yaml")) {
            var def = new DslLoader().load(in, "dlq-summary.yaml", LoadOptions.STRICT);
            assertThat(def.schedule().cron()).isNotNull();
            assertThat(def.schedule().on()).isEmpty();
        }
    }

    @Test
    void an_occurrence_with_parked_sdds_mails_the_technician_the_list_once() throws Exception {
        parked.park(new ParkedSdds.Parked("p9", "1", "dlq-sdd", 5));
        parked.park(new ParkedSdds.Parked("p2", "2", "p2-sdd-1", 10));
        try (InMemoryDomain d = new InMemoryDomain("delivery", "dlq-summary.yaml", this::handlers)) {
            d.run(occurrence(d, T), r -> null, Duration.ofSeconds(5));

            assertThat(mails).singleElement().satisfies(mail -> {
                assertThat(mail.path("template").asText()).isEqualTo("dlq-summary");
                assertThat(mail.path("toRole").asText()).isEqualTo("technician");
                String body = new String(d.blobs.data.get(URI.create(mail.path("documentRef").asText())),
                        StandardCharsets.UTF_8);
                assertThat(body).contains("waiting for the technician: 2").contains("- p2 c2 p2-sdd-1 (10 attempts)")
                        .contains("- p9 c1 dlq-sdd (5 attempts)");
                assertThat(body.indexOf("p2-sdd-1")).as("by project").isLessThan(body.indexOf("dlq-sdd"));
            });
        }
    }

    @Test
    void an_occurrence_with_nothing_parked_sends_nothing() throws Exception {
        try (InMemoryDomain d = new InMemoryDomain("delivery", "dlq-summary.yaml", this::handlers)) {
            var done = d.run(occurrence(d, T), r -> null, Duration.ofSeconds(5));

            assertThat(InMemoryDomain.terminal(done)).isTrue();
            assertThat(mails).isEmpty();
            assertThat(d.blobs.data).isEmpty();
        }
    }
}
