package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.Portal;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SDD S.5, CA3 (8g): on floci, with a business design waiting for the business representative's approval, nothing
 * but the right role through the portal moves it.
 * <ol>
 *   <li>Through the portal: another role gets 403, an answer out of the review contract 422 — nothing is published.</li>
 *   <li>Forged on the bus, past the portal: another role ({@code wrong-role}), an unknown source
 *       ({@code untrusted-source}) and free text ({@code out-of-contract}) do not resume it; each is in
 *       {@code business-audit}.</li>
 *   <li>The right role through the portal resumes it: design-published.</li>
 * </ol>
 * The early answer of an escalation role (Stint 3a.4 finding) is refused by the portal (step 1: a role answers only a
 * review the engine opened for it); on the bus it is a known Stint finding, recorded in S.5 9.5, not asserted here.
 */
class PortalRolesIT extends ScenarioBase {

    @Test
    void only_the_right_role_through_the_portal_moves_a_review() throws Exception {
        for (Module m : List.of(Module.PRIVACY, Module.RECORDS, Module.CHANNELS, Module.BUSINESS)) {
            start(m.name().toLowerCase(), m, Map.of());
        }
        Portal portal = new Portal(Module.CHANNELS.port());
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        List<JsonNode> open = new ArrayList<>();
        spy.until("the business representative's review is open in the portal", () -> {
            open.clear();
            open.addAll(portal.open(PipelineEvents.ROLE_BUSINESS));
            return !open.isEmpty();
        }, FLOW);
        JsonNode review = open.getFirst();
        String reviewId = review.path("reviewId").asText();
        note("review %s open for %s", reviewId, review.path("roles"));
        ObjectNode approve = Spy.JSON.createObjectNode().put("verdict", "approved");

        // 1. the portal refuses
        int wrongRole = portal.answer(PipelineEvents.ROLE_TECHNICIAN, reviewId, approve);
        int freeText = portal.answer(PipelineEvents.ROLE_BUSINESS, reviewId, approve.deepCopy()
                .put("comment", "fine, but call SYNTH-PII-2011 first"));
        note("portal: technician %d, free text %d", wrongRole, freeText);
        assertThat(wrongRole).isEqualTo(403);
        assertThat(freeText).isEqualTo(422);

        // 2. forged on the bus: the engine's own checks
        String instanceId = reviewId.substring(0, reviewId.indexOf(':'));
        forge(reviewId, PipelineEvents.SOURCE_PORTAL, PipelineEvents.ROLE_TECHNICIAN, approve);
        forge(reviewId, "https://somewhere-else.example", PipelineEvents.ROLE_BUSINESS, approve);
        forge(reviewId, PipelineEvents.SOURCE_PORTAL, PipelineEvents.ROLE_BUSINESS, approve.deepCopy()
                .put("comment", "free text"));
        List<String> outcomes = new ArrayList<>();
        spy.until("the three forged answers audited", () -> {
            outcomes.clear();
            outcomes.addAll(auditOutcomes(instanceId));
            return outcomes.containsAll(List.of("wrong-role", "untrusted-source", "out-of-contract"));
        }, FLOW);
        note("business-audit outcomes: %s", outcomes);
        assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();
        assertThat(waitsOf("business", instanceId)).as("still waiting").isPositive();

        // 3. the right role through the portal
        assertThat(portal.answer(PipelineEvents.ROLE_BUSINESS, reviewId, approve)).isEqualTo(202);
        spy.untilEvents("design-published", ev -> ev.stream()
                .anyMatch(e -> e.type().equals(PipelineEvents.DESIGN_PUBLISHED)), FLOW);
        note("design-published after the business representative's answer");

        Scenario.evidence("S.5-CA3.txt", "S.5 CA3: only the right role through the portal moves a review\n"
                + timeline() + "  review " + reviewId + "\n  portal: technician -> " + wrongRole + ", free text -> "
                + freeText + "\n  forged on the bus -> business-audit outcomes " + outcomes
                + "\n  right role through the portal -> 202, design-published\n");
    }

    /** An answer published straight on the bus, past the portal (as anyone with access to the bus could). */
    void forge(String reviewId, String source, String role, ObjectNode answer) {
        ObjectNode data = answer.deepCopy().put("reviewer", "rev-forged");
        ObjectNode ce = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", UUID.randomUUID().toString())
                .put("source", source).put("type", PipelineEvents.REVIEW_COMPLETED).put("subject", reviewId)
                .put("datacontenttype", "application/json").put("stintrole", role)
                .put(PipelineEvents.EXT_DOMAIN, "business");
        ce.set("data", data);
        floci.eventBridge.putEvents(r -> r.entries(e -> e.eventBusName("sandbox-domain").source(source)
                .detailType(PipelineEvents.REVIEW_COMPLETED).detail(ce.toString())));
        note("forged %s from %s as %s", answer.has("comment") ? "free text" : "approval", source, role);
    }

    List<String> auditOutcomes(String instanceId) {
        List<String> out = new ArrayList<>();
        floci.ddb.query(r -> r.tableName("business-audit").keyConditionExpression("instanceId = :i")
                .expressionAttributeValues(Map.of(":i", AttributeValue.fromS(instanceId))).consistentRead(true))
                .items().forEach(i -> {
                    if ("review".equals(i.get("kind").s()) && i.containsKey("outcome")) {
                        out.add(i.get("outcome").s());
                    }
                });
        return out;
    }
}
