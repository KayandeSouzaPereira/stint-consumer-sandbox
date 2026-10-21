package io.stintflow.sandbox.privacy.filter;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SDD S.6, 8a: the {@code noul} question to a local LitJev (Stint 3a.2b): {@code POST /v1/systemone} with the text as
 * the state and one {@code noul} question with both its criteria (the server refuses one without), answered with a
 * probability. Loopback only — the text is confidential: a base URI that is not a loopback address is refused when this
 * is built, so the text can never leave the host through a configuration slip.
 */
public final class LitJevPersonalDataCheck implements PersonalDataCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUESTION = "personal-data";

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    public LitJevPersonalDataCheck(URI baseUri, String model, Duration timeout) throws java.io.IOException {
        if (!InetAddress.getByName(baseUri.getHost()).isLoopbackAddress()) {
            throw new IllegalArgumentException("the personal-data check runs on loopback only, not on " + baseUri.getHost());
        }
        this.endpoint = baseUri.resolve("/v1/systemone");
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public double likelihood(String text) throws Exception {
        ObjectNode question = JSON.createObjectNode().put("type", "noul").put("instructions",
                "Does this text still contain personal data: a person's name, e-mail address, phone number or ID?");
        question.putObject("criteria").put("true", "It contains personal data").put("false", "It contains no personal data");
        ObjectNode body = JSON.createObjectNode().put("model", model).put("state", text);
        body.putObject("questions").set(QUESTION, question);
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LitJev answered " + response.statusCode());
        }
        JsonNode noul = JSON.readTree(response.body()).path("answers").path(QUESTION).path("noul");
        if (!noul.isNumber()) {
            throw new IllegalStateException("LitJev's answer has no noul");
        }
        return noul.asDouble();
    }
}
