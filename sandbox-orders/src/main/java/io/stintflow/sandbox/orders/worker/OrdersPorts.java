package io.stintflow.sandbox.orders.worker;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * SDD S.3: what the Orders worker's handlers need from the outside world. The AWS/SMTP implementations live in
 * {@code io.stintflow.sandbox.orders.aws}; the unit tests (CA8) use in-memory ones.
 */
public final class OrdersPorts {

    private OrdersPorts() {
    }

    /** RF3: reads exactly one invoice document, the one an invoice-issued event cites. */
    public interface InvoiceDocuments {
        byte[] read(URI document);
    }

    /** RF4: markers of effects that must not happen twice. */
    public interface EffectMarkers {
        /**
         * Writes the marker at {@code now} unless it exists. Returns empty when this call wrote it (the effect may
         * proceed), or the instant it was first written (the effect was already started).
         */
        Optional<Instant> markIfAbsent(String effectKey, Instant now);
    }

    public record Attachment(String name, String contentType, byte[] content) {
    }

    /** S.3 8e: sends a plain-text e-mail, optionally with one attachment. */
    public interface MailSender {
        void send(String from, String to, String subject, String body, Attachment attachment);
    }
}
