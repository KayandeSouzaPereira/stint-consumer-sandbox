package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * SDD S.14, CA6: Business stops answering. An ADR session drafted while it is down is drafted two ways. With
 * {@code follow} (the default) the ADR goes on without the context: the pair goes to a person, never to compatible, and the
 * ADR says the resolution was unavailable. With {@code suspend} the failure ends the instance and nothing is published; when
 * Business is back the same session, drafted again, is concluded.
 */
class BridgeOutageIT extends PipelineScenario {

    static final String OPERATOR = "ops";
    static final String DRAFTED = "io.sandbox.engineering.architecture-decisions-drafted.v1";
    static final String PUBLISHED = "io.sandbox.engineering.adrs-published.v1";

    @Test
    void with_business_down_follow_goes_on_without_the_context_and_suspend_waits_for_it() throws Exception {
        Map<Module, App> running = startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR)));
        KnowledgePlant plant = new KnowledgePlant(floci);
        plant.register("rec-ou-1", 1, "The limit is three books per member.", "ou");
        spy.until("references linked", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.engineering.references-linked.v1")), Duration.ofMinutes(3));
        spy.until("pointers resolved while Business is up", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.engineering.pointers-resolved.v1")), Duration.ofMinutes(2));
        String pointer = Spy.JSON.readTree(spy.events().stream().filter(e -> e.type().equals(
                "io.sandbox.business.nodes-published.v1")).findFirst().orElseThrow().json()).path("data").path("items")
                .get(0).path("pointer").asText();

        running.get(Module.BUSINESS).kill();
        spy.settle(Duration.ofSeconds(3));
        note("Business killed");

        // --- follow: the ADR goes on without the context, the pair goes to a person ---
        draft("ou-follow", pointer, "follow");
        awaitReviewAndAnswer("compatible");
        Spy.Event followed = await("ou-follow", Duration.ofMinutes(4));
        JsonNode data = Spy.JSON.readTree(followed.json()).path("data");
        assertThat(data.path("resolution").asText()).isEqualTo("unavailable");
        assertThat(data.path("counts").path("decisions").asInt()).isEqualTo(1);

        // --- suspend: nothing is published while Business is down ---
        draft("ou-suspend", pointer, "suspend");
        spy.until("the instance ended in a failure", () -> failed("architecture-adr") >= 1, Duration.ofMinutes(3));
        assertThat(spy.events().stream().filter(e -> e.type().equals(PUBLISHED) && "ou-suspend".equals(subject(e))))
                .as("nothing published while Business is down").isEmpty();

        // --- Business is back: the same session, drafted again, is concluded ---
        App back = start("business-back", Module.BUSINESS, Map.of("business.knowledge.operators", OPERATOR,
                "business.engine.enabled", "false", "quarkus.http.port", "8097"));
        draft("ou-suspend-2", pointer, "suspend");
        Spy.Event concluded = await("ou-suspend-2", Duration.ofMinutes(4));
        assertThat(Spy.JSON.readTree(concluded.json()).path("data").path("resolution").asText()).isEqualTo("resolved");
        Scenario.evidence("S.14-CA6.txt", "S.14 CA6: Business stops answering the pointers\n" + timeline()
                + "  follow: the ADR went on without the context (resolution unavailable), the pair went to a person\n"
                + "  suspend: the instance ended in a failure, nothing published; with Business back the session concluded"
                + " (resolution resolved)\n");
        back.kill();
    }

    private void draft(String sessionId, String pointer, String onUnavailable) throws Exception {
        ObjectNode draft = Spy.JSON.createObjectNode();
        ArrayNode decisions = draft.putArray("decisions");
        ObjectNode one = decisions.addObject().put("id", "adr-" + sessionId).put("title", "Enforce the limit in the loans service")
                .put("summary", "The loans service checks the limit when a loan is created.");
        one.putArray("motivatedBy").add(pointer);
        String key = "adr-draft/" + sessionId + ".json";
        floci.s3.putObject(r -> r.bucket("sandbox-engineering-blobs").key(key), RequestBody.fromBytes(
                Spy.JSON.writeValueAsString(draft).getBytes(StandardCharsets.UTF_8)));
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", sessionId + ":drafted")
                .put("type", DRAFTED).put("source", "https://sandbox.stintflow.io/engineering").put("subject", sessionId)
                .put("datacontenttype", "application/json").put("sandboxproject", "ou").put("sandboxcycle", "1");
        event.putObject("data").put("sessionId", sessionId).put("draftRef", "s3://sandbox-engineering-blobs/" + key)
                .put("label", "confidential").put("onUnavailable", onUnavailable);
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/engineering").detailType(DRAFTED).detail(detail).build()));
        note("drafted %s (%s)", sessionId, onUnavailable);
    }

    private void awaitReviewAndAnswer(String value) {
        spy.until("the review of the pair opened and answered", () -> answerOpenConflicts(value), Duration.ofMinutes(4));
    }

    /** Answers the open conflict reviews with {@code value}; true if it answered one. */
    private boolean answerOpenConflicts(String value) {
        boolean any = false;
        for (JsonNode review : portal.open("tech-rep")) {
            String id = review.path("reviewId").asText();
            if ("choice".equals(review.path("kind").asText()) && review.toString().contains("conflicts")
                    && !answered.contains(id) && portal.answer("tech-rep", id, Spy.JSON.createObjectNode().put("value",
                            value)) == 202) {
                answered.add(id);
                note("answered %s with %s", id, value);
                any = true;
            }
        }
        return any;
    }

    private Spy.Event await(String sessionId, Duration within) {
        spy.until("ADRs of " + sessionId, () -> {
            answerOpenConflicts("compatible");
            return spy.events().stream().anyMatch(e -> e.type().equals(PUBLISHED) && sessionId.equals(subject(e)));
        }, within);
        return spy.events().stream().filter(e -> e.type().equals(PUBLISHED) && sessionId.equals(subject(e))).findFirst()
                .orElseThrow();
    }

    private long failed(String definition) {
        return floci.ddb.scan(r -> r.tableName("engineering-instances")).items().stream()
                .filter(i -> "FAILED".equals(text(i.get("status"))) && text(i.get("definition")).contains(definition)).count();
    }

    private static String text(AttributeValue v) {
        return v == null || v.s() == null ? "" : v.s();
    }
}
