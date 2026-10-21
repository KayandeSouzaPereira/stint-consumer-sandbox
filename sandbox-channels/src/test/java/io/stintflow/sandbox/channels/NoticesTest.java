package io.stintflow.sandbox.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.mailer.Mailer;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.8, RF3: when a design's approval is escalated, every lead is told by e-mail; a first request tells no one.
 */
class NoticesTest {

    final List<io.quarkus.mailer.Mail> sent = new ArrayList<>();
    final Mailer mailer = new Mailer() {
        @Override
        public void send(io.quarkus.mailer.Mail... mails) {
            sent.addAll(List.of(mails));
        }
    };
    final Portal portal = new Portal(e -> { }, URI.create(PipelineEvents.SOURCE_PORTAL));
    final Notices notices = new Notices(portal, new Mail(mailer, noDocuments(), "sandbox@sandbox.local"), null, false);

    /** The notices read no document; the mail only needs a store to exist. */
    static io.stintflow.spi.BlobStore noDocuments() {
        return new io.stintflow.spi.BlobStore() {
            @Override
            public java.util.concurrent.CompletionStage<URI> put(byte[] bytes, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletionStage<byte[]> get(URI ref) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> delete(URI ref) {
                throw new UnsupportedOperationException();
            }
        };
    }

    static CloudEvent request(String id, String role, String escalatedFrom) {
        return request(id, role, escalatedFrom, "s3://sandbox-documents/design/p1-c1-design.md");
    }

    static CloudEvent request(String id, String role, String escalatedFrom, String material) {
        ObjectNode data = Json.obj().put("kind", "approval").put("materialRef", material);
        data.putArray("options");
        data.putArray("items");
        if (escalatedFrom != null) {
            data.put("escalatedFrom", escalatedFrom);
        }
        return CloudEventBuilder.v1().withId(id).withSource(URI.create("stint://engine/review"))
                .withType(StintEvents.TYPE_REVIEW_REQUESTED).withSubject("evt-1:/do/9/approve:1")
                .withData(Json.bytes(data)).withExtension(StintEvents.EXT_ROLE, role)
                .withExtension(PipelineEvents.EXT_DOMAIN, "business").build();
    }

    @Test
    void an_escalated_approval_tells_every_lead_and_a_first_request_tells_no_one() throws Exception {
        notices.on(request("r-1", "business-rep", null));
        assertThat(sent).isEmpty();

        notices.on(request("r-2", "business-lead", "business-rep"));
        assertThat(sent).extracting(m -> m.getTo().getFirst()).containsExactlyInAnyOrder(
                "business-lead@sandbox.local", "tech-rep@sandbox.local");
        assertThat(sent).extracting(io.quarkus.mailer.Mail::getSubject)
                .containsOnly("[p1 c1] Design approval is overdue");

        notices.on(request("r-2", "business-lead", "business-rep"));   // the same event again
        assertThat(sent).as("a redelivered escalation tells no one twice").hasSize(2);
    }

    @Test
    void an_escalated_batch_of_sdds_tells_the_technical_and_business_leads() throws Exception {
        notices.on(request("r-3", "tech-lead", "tech-rep", "s3://sandbox-documents/sdd/p2-c2-batch.json"));
        assertThat(sent).extracting(m -> m.getTo().getFirst()).containsExactlyInAnyOrder(
                "tech-lead@sandbox.local", "business-lead@sandbox.local");
        assertThat(sent).extracting(io.quarkus.mailer.Mail::getSubject)
                .containsOnly("[p2 c2] Design approval is overdue");
    }
    /** SDD S.10, CA5: the daily summary of the DLQ goes to the technician with the listed ids as its body. */
    @Test
    void the_dlq_summary_goes_to_the_technician_with_the_list_as_its_body() throws Exception {
        var documents = new io.stintflow.spi.BlobStore() {
            @Override
            public java.util.concurrent.CompletionStage<URI> put(byte[] bytes, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletionStage<byte[]> get(URI ref) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        "SDDs waiting for the technician: 1\n- p9 c1 dlq-sdd (5 attempts)\n".getBytes());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> delete(URI ref) {
                throw new UnsupportedOperationException();
            }
        };
        String key = new Mail(mailer, documents, "sandbox@sandbox.local").send("dlq-summary", "technician", "delivery",
                "0", Json.obj().textNode("s3://sandbox-documents/dlq/summary-2026-10-21.txt"), "");

        assertThat(key).isEqualTo("[delivery c0] SDDs waiting for the technician");
        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.getTo()).containsExactly("technician@sandbox.local");
            assertThat(m.getText()).contains("p9 c1 dlq-sdd (5 attempts)");
        });
    }
}
