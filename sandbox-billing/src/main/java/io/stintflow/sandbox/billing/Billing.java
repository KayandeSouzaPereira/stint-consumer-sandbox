package io.stintflow.sandbox.billing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.stintflow.spi.WorkflowRef;

/**
 * SDD S.2: the Billing domain's names. The event types and the source are those of {@code contracts/events.md},
 * the only agreement with other domains; the routes are this domain's own.
 */
public final class Billing {

    public static final WorkflowRef ISSUE_INVOICE = new WorkflowRef("billing", "issue-invoice", "1.0.0");
    public static final WorkflowRef BILLING_SUMMARY = new WorkflowRef("billing", "billing-summary", "1.0.0");

    public static final String ROUTE_RENDER_INVOICE = "render-invoice";
    public static final String ROUTE_RECORD_INVOICE = "record-invoice";
    public static final String ROUTE_COUNT_INVOICES = "count-invoices";
    public static final String ROUTE_SEND_SUMMARY = "send-summary";

    public static final URI SOURCE = URI.create("https://sandbox.stintflow.io/billing");
    public static final String INVOICE_REQUESTED = "io.sandbox.billing.invoice-requested.v1";
    public static final String INVOICE_ISSUED = "io.sandbox.billing.invoice-issued.v1";
    public static final String INVOICE_REJECTED = "io.sandbox.billing.invoice-rejected.v1";

    /** DSL 1.0 standard error type of a transient failure to reach a dependency (Stint SDD 2.8). */
    public static final URI COMMUNICATION_ERROR =
            URI.create("https://serverlessworkflow.io/spec/1.0.0/errors/communication");

    private Billing() {
    }

    /** RF1: the invoice of a request is a function of its {@code orderId}, so every retry and redelivery agrees. */
    public static String invoiceId(String orderId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(orderId.getBytes(StandardCharsets.UTF_8));
            return "inv-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
