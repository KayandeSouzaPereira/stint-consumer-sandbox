package io.stintflow.sandbox.billing.worker;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

/**
 * SDD S.2: what the Billing worker's handlers need from the outside world. The AWS/SMTP implementations live in
 * {@code io.stintflow.sandbox.billing.aws}; the unit tests (CA8) use in-memory ones.
 */
public final class BillingPorts {

    private BillingPorts() {
    }

    /** An issued invoice, as recorded (S.2, 8c). */
    public record Invoice(String invoiceId, String orderId, BigDecimal amount, String currency, Instant issuedAt) {
    }

    /** S.2, 8a: invoice documents under {@code billing/invoices/} in {@code sandbox-facts}. */
    public interface InvoiceDocuments {
        /** Stores the document under a key that is a function of the invoice id, and returns its URI. */
        URI put(String invoiceId, String html);
    }

    /** S.2, 8c: the invoice records. */
    public interface InvoiceRecords {
        /** Records the invoice unless one with its id exists; either way returns the recorded one. */
        Invoice recordIfAbsent(Invoice invoice);

        /** How many invoices were issued in {@code [from, to)}. */
        long countIssued(Instant from, Instant to);
    }

    /** RF5: markers of effects that must not happen twice. */
    public interface EffectMarkers {
        /** Writes the marker; {@code false} when it already existed (the effect was already started). */
        boolean markIfAbsent(String effectKey);
    }

    /** S.2, 8e: sends a plain-text e-mail. */
    public interface MailSender {
        void send(String from, String to, String subject, String body);
    }
}
