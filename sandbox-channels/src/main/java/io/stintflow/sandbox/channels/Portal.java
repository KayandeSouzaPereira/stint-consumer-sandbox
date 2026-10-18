package io.stintflow.sandbox.channels;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;
import io.stintflow.wire.review.ReviewContract;

/**
 * SDD S.5, 8g: the approval portal. It keeps the open reviews from the engines' {@code review.requested} (ids, kind,
 * options, items, the material's pointer — never the material) by role, and turns an authenticated person's answer
 * into {@code io.stintflow.review.completed.v1} with the portal's {@code source} — the one every pipeline workflow
 * trusts — the person's role from their credential, an opaque reviewer id and the review's domain for routing.
 * <ul>
 *   <li>A review that is not open for the person's role is refused (403): a role answers only once the engine asked
 *       it — an escalation role cannot answer early (Stint 3a.4 finding).</li>
 *   <li>An answer out of the review contract (free text, an unknown option, other items) is refused (422).</li>
 * </ul>
 */
public final class Portal {

    private static final Logger LOG = Logger.getLogger(Portal.class);

    /** An open review: the roles the engine asked so far (an escalation adds one), and what the answer must hold. */
    public record Open(String reviewId, String domain, String kind, List<String> options, List<String> items,
            Set<String> roles, String chainId, JsonNode request) {
    }

    public enum Outcome {
        PUBLISHED, NOT_OPEN_FOR_ROLE, OUT_OF_CONTRACT
    }

    private final Map<String, Open> open = new ConcurrentHashMap<>();
    private final Consumer<CloudEvent> bus;
    private final URI source;

    /** @param bus publishes onto the domain bus */
    public Portal(Consumer<CloudEvent> bus, URI source) {
        this.bus = bus;
        this.source = source;
    }

    /** A review request (or its escalation) from an engine. */
    public void requested(CloudEvent event) {
        String reviewId = event.getSubject();
        Object role = event.getExtension(StintEvents.EXT_ROLE);
        Object domain = event.getExtension(PipelineEvents.EXT_DOMAIN);
        if (reviewId == null || role == null || domain == null || event.getData() == null) {
            LOG.warnf("Review request %s without id, role or domain: ignored", event.getId());
            return;
        }
        JsonNode data = Json.read(event.getData().toBytes());
        List<String> options = new ArrayList<>();
        data.path("options").forEach(o -> options.add(o.path("name").asText()));
        List<String> items = new ArrayList<>();
        data.path("items").forEach(i -> items.add(i.asText()));
        open.compute(reviewId, (id, existing) -> {
            Set<String> roles = new LinkedHashSet<>(existing == null ? Set.of() : existing.roles());
            roles.add(role.toString());
            ObjectNode request = data.deepCopy();
            request.put("reviewId", reviewId).put("domain", domain.toString());
            request.set("roles", Json.MAPPER.valueToTree(roles));
            Object chain = event.getExtension(StintEvents.EXT_CHAIN_ID);
            return new Open(reviewId, domain.toString(), data.path("kind").asText(), options, items, roles,
                    chain == null ? null : chain.toString(), request);
        });
        LOG.infof("Review %s open for %s (domain %s)", reviewId, role, domain);
    }

    /** The open reviews a role may answer. */
    public List<JsonNode> openFor(String role) {
        return open.values().stream().filter(o -> o.roles().contains(role)).map(Open::request).toList();
    }

    public ArrayNode all() {
        ArrayNode out = Json.MAPPER.createArrayNode();
        open.values().forEach(o -> out.add(o.request()));
        return out;
    }

    /** One person's answer: published, or why not. */
    public Outcome answer(String user, String role, String reviewId, JsonNode answer) {
        Open review = open.get(reviewId);
        if (review == null || !review.roles().contains(role)) {
            LOG.warnf("Answer to %s by role %s refused: not open for that role", reviewId, role);
            return Outcome.NOT_OPEN_FOR_ROLE;
        }
        ObjectNode data = answer.isObject() ? ((ObjectNode) answer).deepCopy() : Json.obj();
        data.put(ReviewContract.REVIEWER, reviewerId(user));
        List<String> violations = ReviewContract.violations(data, review.kind(), review.options(), review.items());
        if (!violations.isEmpty()) {
            LOG.warnf("Answer to %s refused: out of the review contract (%d violation(s))", reviewId,
                    violations.size());
            return Outcome.OUT_OF_CONTRACT;
        }
        CloudEvent completed = completed(source, reviewId, role, review.domain(), data);
        if (review.chainId() != null) {   // the answer stays on the chain of the review it answers (8b)
            completed = CloudEventBuilder.v1(completed).withExtension(StintEvents.EXT_CHAIN_ID, review.chainId()).build();
        }
        bus.accept(completed);
        open.remove(reviewId);
        LOG.infof("Review %s answered by role %s", reviewId, role);
        return Outcome.PUBLISHED;
    }

    /** The answer event — also what a test forges to prove the engine's own checks (CA3). */
    public static CloudEvent completed(URI source, String reviewId, String role, String domain, JsonNode data) {
        return CloudEventBuilder.v1().withId(UUID.randomUUID().toString()).withSource(source)
                .withType(StintEvents.TYPE_REVIEW_COMPLETED).withSubject(reviewId)
                .withDataContentType(StintEvents.CONTENT_TYPE_JSON).withData(Json.bytes(data))
                .withExtension(StintEvents.EXT_ROLE, role).withExtension(PipelineEvents.EXT_DOMAIN, domain).build();
    }

    /** An opaque id for a person: never a name nor an e-mail (the review contract). */
    static String reviewerId(String user) {
        return "rev-" + Corpus.sha256(("portal|" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .substring(0, 16);
    }
}
