package io.stintflow.sandbox.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.5, 8g (CA3, the portal's side): a role answers only a review the engine opened for it — an escalation role
 * only once escalated — and only within the review contract; the answer carries the portal's source, the role from
 * the credential, an opaque reviewer and the review's domain.
 */
class PortalTest {

    final List<CloudEvent> published = new ArrayList<>();
    final Portal portal = new Portal(published::add, URI.create(PipelineEvents.SOURCE_PORTAL));

    static CloudEvent requested(String reviewId, String role, String kind) {
        ObjectNode data = Json.obj().put("kind", kind).put("materialRef", "s3://sandbox-documents/d.md")
                .put("deadlineSeconds", 3600);
        if ("choice".equals(kind)) {
            data.putArray("options").add(Json.obj().put("name", "resend").put("description", "Resend"))
                    .add(Json.obj().put("name", "cancel").put("description", "Cancel"));
        } else {
            data.putArray("options");
        }
        data.putArray("items");
        return CloudEventBuilder.v1().withId("r-" + role).withSource(URI.create("stint://engine/review"))
                .withType(StintEvents.TYPE_REVIEW_REQUESTED).withSubject(reviewId)
                .withData(Json.bytes(data)).withExtension(StintEvents.EXT_ROLE, role)
                .withExtension(PipelineEvents.EXT_DOMAIN, "delivery").build();
    }

    @Test
    void the_right_role_answers_and_the_answer_is_the_portals() {
        portal.requested(requested("evt-1:/do/9/approve:1", "business-rep", "approval"));
        assertThat(portal.openFor("business-rep")).hasSize(1);
        assertThat(portal.openFor("tech-rep")).isEmpty();

        assertThat(portal.answer("test-business-rep", "business-rep", "evt-1:/do/9/approve:1",
                Json.obj().put("verdict", "approved"))).isEqualTo(Portal.Outcome.PUBLISHED);

        CloudEvent completed = published.getFirst();
        assertThat(completed.getSource().toString()).isEqualTo(PipelineEvents.SOURCE_PORTAL);
        assertThat(completed.getExtension(StintEvents.EXT_ROLE)).isEqualTo("business-rep");
        assertThat(completed.getExtension(PipelineEvents.EXT_DOMAIN)).isEqualTo("delivery");
        assertThat(Json.read(completed.getData().toBytes()).path("reviewer").asText()).startsWith("rev-")
                .doesNotContain("business").doesNotContain("@");
        assertThat(portal.openFor("business-rep")).isEmpty();
    }

    @Test
    void another_role_is_refused_until_the_engine_escalates_to_it() {
        portal.requested(requested("evt-2:/do/3/dlq:1", "technician", "choice"));
        assertThat(portal.answer("test-tech-rep", "tech-rep", "evt-2:/do/3/dlq:1", Json.obj().put("value", "cancel")))
                .isEqualTo(Portal.Outcome.NOT_OPEN_FOR_ROLE);
        portal.requested(requested("evt-2:/do/3/dlq:1", "tech-rep", "choice"));   // the escalation
        assertThat(portal.answer("test-tech-rep", "tech-rep", "evt-2:/do/3/dlq:1", Json.obj().put("value", "cancel")))
                .isEqualTo(Portal.Outcome.PUBLISHED);
        assertThat(published).hasSize(1);
    }

    @Test
    void an_answer_out_of_the_review_contract_is_refused() {
        portal.requested(requested("evt-3:/do/3/dlq:1", "technician", "choice"));
        assertThat(portal.answer("test-technician", "technician", "evt-3:/do/3/dlq:1",
                Json.obj().put("value", "rewrite-everything"))).isEqualTo(Portal.Outcome.OUT_OF_CONTRACT);
        assertThat(portal.answer("test-technician", "technician", "evt-3:/do/3/dlq:1",
                Json.obj().put("value", "cancel").put("comment", "the SDD was wrong, ask SYNTH-PII-2011")))
                .isEqualTo(Portal.Outcome.OUT_OF_CONTRACT);
        assertThat(published).isEmpty();
        assertThat(portal.answer("test-technician", "technician", "unknown", Json.obj().put("value", "cancel")))
                .isEqualTo(Portal.Outcome.NOT_OPEN_FOR_ROLE);
    }
}
