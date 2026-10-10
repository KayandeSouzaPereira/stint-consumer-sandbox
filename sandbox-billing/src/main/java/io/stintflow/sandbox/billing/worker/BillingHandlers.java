package io.stintflow.sandbox.billing.worker;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.billing.Billing;
import io.stintflow.sandbox.billing.worker.BillingPorts.EffectMarkers;
import io.stintflow.sandbox.billing.worker.BillingPorts.Invoice;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceDocuments;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceRecords;
import io.stintflow.sandbox.billing.worker.BillingPorts.MailSender;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.2: the Billing worker's task handlers. Every effect is idempotent (RF5):
 * <ul>
 *   <li>{@code render-invoice}: the invoice id and the document key are functions of the {@code orderId}, so a
 *       repeated render overwrites the same object with the same content;</li>
 *   <li>{@code record-invoice}: a conditional write; a repeat returns the invoice as first recorded, with its
 *       original {@code issuedAt};</li>
 *   <li>{@code count-invoices}: a read;</li>
 *   <li>{@code send-summary}: a marker keyed by instance + task, written <em>before</em> sending (at most once:
 *       a crash between the marker and the SMTP call loses that summary rather than sending it twice).</li>
 * </ul>
 */
public final class BillingHandlers {

    /** Logged inside the worker's Stint log context: every line carries stint.chainId, stint.instanceId... (RF6). */
    private static final Logger LOG = Logger.getLogger(BillingHandlers.class);

    private final InvoiceDocuments documents;
    private final InvoiceRecords records;
    private final EffectMarkers markers;
    private final MailSender mail;
    private final FaultInjection faults;
    private final InstantSource clock;
    private final String mailFrom;
    private final String mailTo;

    public BillingHandlers(InvoiceDocuments documents, InvoiceRecords records, EffectMarkers markers, MailSender mail,
            FaultInjection faults, InstantSource clock, String mailFrom, String mailTo) {
        this.documents = documents;
        this.records = records;
        this.markers = markers;
        this.mail = mail;
        this.faults = faults;
        this.clock = clock;
        this.mailFrom = mailFrom;
        this.mailTo = mailTo;
    }

    /** The registry the worker runs. */
    public TaskHandlerRegistry registry() {
        return new TaskHandlerRegistry()
                .register(Billing.ROUTE_RENDER_INVOICE, this::renderInvoice)
                .register(Billing.ROUTE_RECORD_INVOICE, ctx -> call(() -> recordInvoice(ctx)))
                .register(Billing.ROUTE_COUNT_INVOICES, ctx -> call(() -> countInvoices(ctx)))
                .register(Billing.ROUTE_SEND_SUMMARY, ctx -> call(() -> sendSummary(ctx)));
    }

    /** The {@code effectKey} of a summary e-mail (RF5). */
    public static String mailEffectKey(String instanceId, String taskId) {
        return "mail#" + instanceId + "#" + taskId;
    }

    CompletionStage<JsonNode> renderInvoice(TaskContext ctx) {
        JsonNode request = ctx.input().path("request");
        String orderId = request.path("orderId").asText();
        Duration delay = faults.delayFor(orderId);
        Executor executor = delay.isZero() ? Runnable::run
                : CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> {
            faults.beforeRender(orderId, ctx.attempt());
            String invoiceId = Billing.invoiceId(orderId);
            URI document = documents.put(invoiceId, InvoiceDocument.html(invoiceId, request));
            LOG.infof("Rendered invoice %s to %s (attempt %d)", invoiceId, document, ctx.attempt());
            ObjectNode out = Json.obj();
            out.put("invoiceId", invoiceId);
            out.put("invoiceDocument", document.toString());
            out.put("renderAttempt", ctx.attempt());
            return (JsonNode) out;
        }, executor);
    }

    JsonNode recordInvoice(TaskContext ctx) {
        JsonNode invoice = ctx.input().path("invoice");
        Invoice recorded = records.recordIfAbsent(new Invoice(invoice.path("invoiceId").asText(),
                invoice.path("orderId").asText(), invoice.path("amount").decimalValue(),
                invoice.path("currency").asText(), clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        LOG.infof("Recorded invoice %s issued at %s", recorded.invoiceId(), recorded.issuedAt());
        ObjectNode out = Json.obj();
        out.put("issuedAt", recorded.issuedAt().toString());
        return out;
    }

    JsonNode countInvoices(TaskContext ctx) {
        Instant windowEnd = OffsetDateTime.parse(ctx.input().path("windowEnd").asText()).toInstant();
        Instant windowStart = windowEnd.minus(Duration.ofMinutes(ctx.input().path("windowMinutes").asLong()));
        ObjectNode out = Json.obj();
        out.put("windowStart", windowStart.toString());
        out.put("windowEnd", windowEnd.toString());
        out.put("invoiceCount", records.countIssued(windowStart, windowEnd));
        LOG.infof("Counted %d invoice(s) issued in [%s, %s)", out.get("invoiceCount").asLong(), windowStart, windowEnd);
        return out;
    }

    JsonNode sendSummary(TaskContext ctx) {
        JsonNode summary = ctx.input().path("summary");
        ObjectNode out = Json.obj();
        if (!markers.markIfAbsent(mailEffectKey(ctx.instanceId(), ctx.taskId()))) {
            LOG.infof("Summary of %s already mailed by an earlier delivery; not sending again", ctx.instanceId());
            out.put("mailed", false); // already sent (or started) by an earlier delivery of this task
            return out;
        }
        long count = summary.path("invoiceCount").asLong();
        String window = summary.path("windowStart").asText() + " to " + summary.path("windowEnd").asText();
        mail.send(mailFrom, mailTo, "Billing summary " + window + " UTC: " + count + " invoice(s)",
                "Invoices issued in [" + summary.path("windowStart").asText() + ", "
                        + summary.path("windowEnd").asText() + ") UTC: " + count + "\n");
        LOG.infof("Mailed the summary to %s", mailTo);
        out.put("mailed", true);
        return out;
    }

    private static CompletionStage<JsonNode> call(Supplier<JsonNode> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** The demonstration invoice document (not a fiscal format, S.2 sec. 5). */
    static final class InvoiceDocument {

        private InvoiceDocument() {
        }

        static String html(String invoiceId, JsonNode request) {
            StringBuilder items = new StringBuilder();
            for (JsonNode item : request.path("items")) {
                items.append("<tr><td>").append(escape(item.path("sku").asText())).append("</td><td>")
                        .append(item.path("quantity").asText()).append("</td><td>")
                        .append(escape(item.path("unitPrice").asText())).append("</td></tr>");
            }
            BigDecimal amount = request.path("amount").decimalValue();
            return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Invoice " + escape(invoiceId)
                    + "</title></head><body><h1>Invoice " + escape(invoiceId) + "</h1>"
                    + "<p>Order " + escape(request.path("orderId").asText()) + " · customer "
                    + escape(request.path("customerId").asText()) + "</p>"
                    + "<table><tr><th>SKU</th><th>Qty</th><th>Unit price</th></tr>" + items + "</table>"
                    + "<p>Total: " + amount.toPlainString() + " " + escape(request.path("currency").asText())
                    + "</p></body></html>";
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
