package io.stintflow.sandbox.orders.worker;

import java.net.URI;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.orders.Orders;
import io.stintflow.sandbox.orders.worker.OrdersPorts.Attachment;
import io.stintflow.sandbox.orders.worker.OrdersPorts.EffectMarkers;
import io.stintflow.sandbox.orders.worker.OrdersPorts.InvoiceDocuments;
import io.stintflow.sandbox.orders.worker.OrdersPorts.MailSender;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.3: the Orders worker's task handlers, the three e-mails of S.3 8e. Each one writes its marker
 * {@code mail#<instanceId>#<taskId>} <em>before</em> sending (RF4, at most once: a crash between the marker and the
 * SMTP call loses that e-mail rather than sending it twice). A repeat finds the marker, sends nothing, and answers
 * with the first attempt's instant, so the workflow sees the same result either way.
 */
public final class OrdersHandlers {

    /** Logged inside the worker's Stint log context: every line carries stint.chainId, stint.instanceId... (RF5). */
    private static final Logger LOG = Logger.getLogger(OrdersHandlers.class);

    private final InvoiceDocuments documents;
    private final EffectMarkers markers;
    private final MailSender mail;
    private final InstantSource clock;
    private final String mailFrom;
    private final Pattern allowedDocument;

    /**
     * @param factsBucket the bucket invoice documents are published to; RF3 accepts only
     *                    {@code s3://<factsBucket>/billing/invoices/<name>.html}, the contract's pattern
     */
    public OrdersHandlers(InvoiceDocuments documents, EffectMarkers markers, MailSender mail, InstantSource clock,
            String mailFrom, String factsBucket) {
        this.documents = documents;
        this.markers = markers;
        this.mail = mail;
        this.clock = clock;
        this.mailFrom = mailFrom;
        this.allowedDocument = Pattern.compile("s3://" + Pattern.quote(factsBucket) + "/billing/invoices/[A-Za-z0-9-]+\\.html");
    }

    public TaskHandlerRegistry registry() {
        return new TaskHandlerRegistry()
                .register(Orders.ROUTE_SEND_CONFIRMATION, ctx -> call(() -> sendConfirmation(ctx)))
                .register(Orders.ROUTE_SEND_REJECTION, ctx -> call(() -> sendRejection(ctx)))
                .register(Orders.ROUTE_SEND_DELAY_NOTICE, ctx -> call(() -> sendDelayNotice(ctx)));
    }

    public static String mailEffectKey(String instanceId, String taskId) {
        return "mail#" + instanceId + "#" + taskId;
    }

    JsonNode sendConfirmation(TaskContext ctx) {
        JsonNode order = ctx.input().path("order");
        JsonNode invoice = ctx.input().path("invoice");
        String orderId = order.path("orderId").asText();
        String invoiceId = invoice.path("invoiceId").asText();
        // RF3: read the cited document first (a bad citation fails the task before any marker is written).
        Attachment attachment = null;
        if (invoice.hasNonNull("invoiceDocument")) {
            URI document = checkedDocument(invoice.get("invoiceDocument").asText());
            attachment = new Attachment("invoice-" + invoiceId + ".html", "text/html", documents.read(document));
        }
        String body = "Your order " + orderId + " is confirmed.\n"
                + "Invoice " + invoiceId + ": " + invoice.path("amount").asText() + " "
                + invoice.path("currency").asText() + ", issued at " + invoice.path("issuedAt").asText() + ".\n"
                + (attachment != null ? "The invoice is attached.\n" : "The invoice document is not available.\n");
        return sendOnce(ctx, order, "Order " + orderId + " confirmed: invoice " + invoiceId, body, attachment);
    }

    JsonNode sendRejection(TaskContext ctx) {
        JsonNode order = ctx.input().path("order");
        String orderId = order.path("orderId").asText();
        String reason = ctx.input().path("reason").asText();
        String explanation = switch (reason) {
            case "invalid-amount" -> "the amount must be a number greater than zero";
            case "missing-currency" -> "the currency is missing";
            default -> "rejected by billing";
        };
        String body = "Your order " + orderId + " could not be invoiced: " + explanation + " (" + reason + ").\n";
        return sendOnce(ctx, order, "Order " + orderId + " was not invoiced: " + reason, body, null);
    }

    JsonNode sendDelayNotice(TaskContext ctx) {
        JsonNode order = ctx.input().path("order");
        String orderId = order.path("orderId").asText();
        String body = "Invoicing of your order " + orderId + " is taking longer than expected.\n"
                + "We will confirm the order by e-mail as soon as the invoice arrives.\n";
        return sendOnce(ctx, order, "Order " + orderId + ": invoicing is delayed", body, null);
    }

    private JsonNode sendOnce(TaskContext ctx, JsonNode order, String subject, String body, Attachment attachment) {
        String recipient = order.path("recipient").asText();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Optional<Instant> earlier = markers.markIfAbsent(mailEffectKey(ctx.instanceId(), ctx.taskId()), now);
        ObjectNode out = Json.obj();
        if (earlier.isPresent()) {
            LOG.infof("'%s' was already sent (or started) at %s; not sending again", subject, earlier.get());
            out.put("mailed", false);
            out.put("sentAt", earlier.get().toString());
            return out;
        }
        mail.send(mailFrom, recipient, subject, body, attachment);
        LOG.infof("Mailed '%s' to %s%s", subject, recipient, attachment != null ? " with " + attachment.name() : "");
        out.put("mailed", true);
        out.put("sentAt", now.toString());
        return out;
    }

    private URI checkedDocument(String citation) {
        if (!allowedDocument.matcher(citation).matches()) {
            throw new IllegalArgumentException("RF3: refusing to read " + citation
                    + ": only invoice documents in the facts bucket, as the contract describes them");
        }
        return URI.create(citation);
    }

    private static CompletionStage<JsonNode> call(Supplier<JsonNode> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
