package io.stintflow.sandbox.channels;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.mailer.Mailer;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ErrorInfo;
import io.stintflow.wire.ClaimCheck;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandler;

/**
 * SDD S.5, 8c: e-mail to the pipeline's roles through MailHog. The task {@code send-email} takes
 * {@code {template, toRole, projectId, cycle, documentRef?}} — ids and a pointer; the channel reads the (redacted)
 * document itself — and the notices of {@link Notices} use the same templates. Subjects carry ids only.
 */
public final class Mail implements TaskHandler {

    public static final String ROUTE = "send-email";
    /** Synthetic addresses of the roles: MailHog only, never a real mailbox. */
    static final Map<String, String> ADDRESSES = Map.of("business-rep", "business-rep@sandbox.local", "tech-rep",
            "tech-rep@sandbox.local", "technician", "technician@sandbox.local");
    static final Map<String, String> SUBJECTS = Map.of("design", "Design document", "meeting", "New meeting requested",
            "invite", "Technical refinement invitation", "questions", "Questions from engineering", "dead-letter",
            "An SDD needs the technician", "cycle-closed", "Cycle closed");

    private final Mailer mailer;
    private final BlobStore documents;
    private final String from;

    public Mail(Mailer mailer, BlobStore documents, String from) {
        this.mailer = Objects.requireNonNull(mailer);
        this.documents = Objects.requireNonNull(documents);
        this.from = from;
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        JsonNode in = ctx.input();
        try {
            String id = send(in.path("template").asText(), in.path("toRole").asText(), in.path("projectId").asText(),
                    in.path("cycle").asText(), in.get("documentRef"), "");
            return CompletableFuture.completedFuture(Json.obj().put("sent", true).put("messageKey", id));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new TaskFailure(ErrorInfo.TYPE_VALIDATION, 400, e.getMessage(),
                    null));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new TaskFailure(ErrorInfo.TYPE_COMMUNICATION, 503,
                    "the mail server or the document store is unavailable", null));
        }
    }

    /** Sends one e-mail; returns its key ({@code [<project> c<cycle>] <template>}). */
    public String send(String template, String toRole, String projectId, String cycle, JsonNode documentRef,
            String text) throws Exception {
        String to = ADDRESSES.get(toRole);
        String subject = SUBJECTS.get(template);
        if (to == null || subject == null || !projectId.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("send-email needs a known template, role and project");
        }
        StringBuilder body = new StringBuilder(text);
        if (documentRef != null && !documentRef.isNull() && !documentRef.isMissingNode()) {
            URI ref = URI.create(ClaimCheck.isPointer(documentRef) ? documentRef.path(ClaimCheck.POINTER_FIELD).asText()
                    : documentRef.asText());
            body.append(new String(documents.get(ref).toCompletableFuture().get(30, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8));
        }
        String key = "[" + projectId + " c" + cycle + "] " + subject;
        mailer.send(io.quarkus.mailer.Mail.withText(to, key, body.toString()).setFrom(from));
        return key;
    }
}
