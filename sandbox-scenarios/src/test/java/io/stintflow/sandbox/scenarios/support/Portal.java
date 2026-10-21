package io.stintflow.sandbox.scenarios.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;

/**
 * SDD S.5, 8g/8h: the approval portal of sandbox-channels, from outside — as a person with a test credential — and
 * the skeleton's scripted reviewer, who answers every review open for a role as the corpus key says: the key's meeting
 * type or channel to a choice, every item approved, and {@code resend} never (the CI's SDDs all pass in the end).
 */
public final class Portal {

    /** The test users of sandbox-channels' application.properties: synthetic, a fixed password. */
    public static final String PASSWORD = "test-only";

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI base;
    private final Set<String> answered = new HashSet<>();

    public Portal(int port) {
        this.base = URI.create("http://localhost:" + port);
    }

    static String user(String role) {
        return "test-" + role;
    }

    /** The reviews open for a role. */
    public List<JsonNode> open(String role) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/reviews"))
                    .header("Authorization", basic(user(role))).GET().build(), HttpResponse.BodyHandlers.ofString());
            List<JsonNode> out = new ArrayList<>();
            Spy.JSON.readTree(r.body()).forEach(out::add);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("the portal is not readable", e);
        }
    }

    /** One answer as {@code role}; returns the HTTP status (202 published, 403 not open for the role, 422 contract). */
    public int answer(String role, String reviewId, JsonNode body) {
        try {
            return http.send(HttpRequest.newBuilder(base.resolve("/reviews/" + reviewId))
                    .header("Authorization", basic(user(role))).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("the portal is not reachable", e);
        }
    }

    /** Records a corpus meeting (the recorder, {@code POST /meetings}). */
    public int record(String meetingId) {
        try {
            return http.send(HttpRequest.newBuilder(base.resolve("/meetings")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"meetingId\":\"" + meetingId + "\"}")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("the recorder is not reachable", e);
        }
    }

    /** What the fake Confluence holds: ids, spaces, titles and labels. */
    public JsonNode pages() {
        try {
            return Spy.JSON.readTree(http.send(HttpRequest.newBuilder(base.resolve("/dev/confluence/pages")).GET()
                    .build(), HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) {
            throw new IllegalStateException("the channels are not readable", e);
        }
    }

    /**
     * The scripted reviewer's round: every review open for any role, answered once, as the corpus key says for the
     * project it belongs to. Returns the review ids answered in this round.
     */
    public List<String> answerAsTheKey(Corpus corpus, String... roles) {
        return answerAsTheKey(corpus, roles, null);
    }

    /** Reviews whose material names one of these tokens get a rejection, once each (SDD S.8, CA7). */
    private final java.util.Set<String> rejectOnce = new java.util.HashSet<>();

    public void rejectFirstApprovalOf(String materialToken) {
        rejectOnce.add(materialToken);
    }

    private List<String> answerAsTheKey(Corpus corpus, String[] roles, Void unused) {
        List<String> done = new ArrayList<>();
        for (String role : roles) {
            for (JsonNode review : open(role)) {
                String id = review.path("reviewId").asText();
                if (answered.contains(id)) {
                    continue;
                }
                ObjectNode body = asTheKey(corpus, review);
                String material = review.path("materialRef").asText();
                if ("approval".equals(review.path("kind").asText()) && review.path("items").isEmpty()) {
                    String token = rejectOnce.stream().filter(material::contains).findFirst().orElse(null);
                    if (token != null) {
                        rejectOnce.remove(token);
                        body = Spy.JSON.createObjectNode().put("verdict", "rejected").put("noteRef",
                                "s3://sandbox-documents/notes/" + id.replaceAll("[^A-Za-z0-9._-]", "-") + ".txt");
                    }
                }
                int status = answer(role, id, body);
                if (status == 202) {
                    answered.add(id);
                    done.add(role + " " + review.path("kind").asText() + " " + id);
                }
            }
        }
        return done;
    }

    static ObjectNode asTheKey(Corpus corpus, JsonNode review) {
        ObjectNode body = Spy.JSON.createObjectNode();
        if ("choice".equals(review.path("kind").asText())) {
            List<String> options = new ArrayList<>();
            review.path("options").forEach(o -> options.add(o.path("name").asText()));
            String value = options.contains("accept") ? "accept" : options.getFirst();
            for (Corpus.Meeting m : corpus.meetings()) {      // the material names its meeting
                if (review.path("materialRef").asText().contains(m.id())) {
                    String key = options.contains("confluence") ? m.expected().channel() : options.contains("sdds")
                            ? m.expected().outcome() : m.expected().meetingType();
                    value = key == null ? value : key;
                }
            }
            return body.put("value", value);
        }
        if (review.path("items").isEmpty()) {
            return body.put("verdict", "approved");
        }
        ArrayNode items = body.putArray("items");
        review.path("items").forEach(i -> items.addObject().put("id", i.asText()).put("verdict", "approved"));
        return body;
    }

    static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
