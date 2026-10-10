package io.stintflow.sandbox.billing.support;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.stintflow.sandbox.billing.Billing;
import io.stintflow.sandbox.billing.aws.S3InvoiceDocuments;
import io.stintflow.sandbox.billing.worker.BillingPorts.EffectMarkers;
import io.stintflow.sandbox.billing.worker.BillingPorts.Invoice;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceDocuments;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceRecords;
import io.stintflow.sandbox.billing.worker.BillingPorts.MailSender;
import io.stintflow.worker.TaskFailure;

/** In-memory stand-ins for the Billing worker's ports (CA8: no floci, no SMTP). */
public final class InMemoryBillingPorts {

    private InMemoryBillingPorts() {
    }

    /** Documents by key, under the same keys as {@link S3InvoiceDocuments}. */
    public static final class Documents implements InvoiceDocuments {
        public final Map<String, String> byKey = new ConcurrentHashMap<>();

        @Override
        public URI put(String invoiceId, String html) {
            String key = S3InvoiceDocuments.key(invoiceId);
            byKey.put(key, html);
            return URI.create("s3://sandbox-facts/" + key);
        }
    }

    /** A renderer whose store is always unavailable: every attempt fails with communication/503. */
    public static final class UnavailableDocuments implements InvoiceDocuments {
        public final AtomicInteger attempts = new AtomicInteger();

        @Override
        public URI put(String invoiceId, String html) {
            attempts.incrementAndGet();
            throw new TaskFailure(Billing.COMMUNICATION_ERROR, 503, "document store unavailable", null);
        }
    }

    public static final class Records implements InvoiceRecords {
        public final Map<String, Invoice> byId = new ConcurrentHashMap<>();

        @Override
        public Invoice recordIfAbsent(Invoice invoice) {
            Invoice existing = byId.putIfAbsent(invoice.invoiceId(), invoice);
            return existing != null ? existing : invoice;
        }

        @Override
        public long countIssued(Instant from, Instant to) {
            return byId.values().stream()
                    .filter(i -> !i.issuedAt().isBefore(from) && i.issuedAt().isBefore(to))
                    .count();
        }
    }

    public static final class Markers implements EffectMarkers {
        public final Set<String> keys = ConcurrentHashMap.newKeySet();

        @Override
        public boolean markIfAbsent(String effectKey) {
            return keys.add(effectKey);
        }
    }

    public record Mail(String from, String to, String subject, String body) {
    }

    public static final class CapturingMail implements MailSender {
        public final List<Mail> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String from, String to, String subject, String body) {
            sent.add(new Mail(from, to, subject, body));
        }
    }
}
