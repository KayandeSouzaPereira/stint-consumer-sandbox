package io.stintflow.sandbox;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends the report notification through the Quarkus Mailer (MailHog for this sandbox), attaching
 * the actual generated file rehydrated from S3 — not just a pointer reference in the body.
 */
@ApplicationScoped
public class SendEmailHandler implements TaskHandler {

    @Inject
    Mailer mailer;
    @Inject
    S3BlobStore blob;

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        String to = ctx.input().get("to").asText();
        String subject = ctx.input().get("subject").asText();
        String body = ctx.input().get("body").asText();
        String attachmentPointer = ctx.input().get("attachment").asText();
        URI ref = URI.create(attachmentPointer);

        return blob.get(ref).thenApply(bytes -> {
            String filename = fileName(ref);
            String contentType = contentTypeFor(filename);
            mailer.send(Mail.withText(to, subject, body).addAttachment(filename, bytes, contentType));

            ObjectNode out = Json.obj();
            out.put("messageId", UUID.randomUUID().toString());
            return out;
        });
    }

    private static String fileName(URI ref) {
        String path = ref.getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String contentTypeFor(String filename) {
        if (filename.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (filename.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }
}
