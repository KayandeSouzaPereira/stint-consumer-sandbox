package io.stintflow.sandbox.scenarios.support;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** SDD S.4: the e-mails MailHog received (its HTTP API), with their attachments decoded. */
public final class MailHog {

    public record Mail(String to, String subject, String body, List<Attachment> attachments) {
    }

    public record Attachment(String name, byte[] content) {
    }

    private final HttpClient http = HttpClient.newHttpClient();

    /** The messages addressed to {@code address}. */
    public List<Mail> to(String address) {
        return read("/api/v2/search?kind=to&limit=1000&query=" + URLEncoder.encode(address, StandardCharsets.UTF_8));
    }

    /** Every message, newest first. */
    public List<Mail> all() {
        return read("/api/v2/messages?limit=5000");
    }

    private List<Mail> read(String path) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(Scenario.MAILHOG.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            List<Mail> mails = new ArrayList<>();
            for (JsonNode item : Spy.JSON.readTree(response.body()).path("items")) {
                String to = item.at("/Content/Headers/To/0").asText();
                String subject = item.at("/Content/Headers/Subject/0").asText();
                String body = item.at("/Content/Body").asText();
                List<Attachment> attachments = new ArrayList<>();
                for (JsonNode part : item.at("/MIME/Parts")) {
                    String disposition = part.at("/Headers/Content-Disposition/0").asText();
                    if (!disposition.startsWith("attachment")) {
                        if (part.at("/Headers/Content-Type/0").asText().startsWith("text/plain")) {
                            byte[] text = decode(part);
                            body = text.length > 0 ? new String(text, StandardCharsets.UTF_8) : body;
                        }
                        continue;
                    }
                    String name = disposition.replaceAll(".*filename=\"?([^\";]+)\"?.*", "$1");
                    attachments.add(new Attachment(name, decode(part)));
                }
                mails.add(new Mail(to, subject, body, attachments));
            }
            return mails;
        } catch (Exception e) {
            throw new IllegalStateException("MailHog at " + Scenario.MAILHOG + " is not readable", e);
        }
    }

    private static byte[] decode(JsonNode part) {
        String body = part.path("Body").asText();
        String encoding = part.at("/Headers/Content-Transfer-Encoding/0").asText();
        if ("base64".equalsIgnoreCase(encoding)) {
            return Base64.getMimeDecoder().decode(body);
        }
        if ("quoted-printable".equalsIgnoreCase(encoding)) {
            return body.replace("=\r\n", "").replace("=\n", "").getBytes(StandardCharsets.UTF_8);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
