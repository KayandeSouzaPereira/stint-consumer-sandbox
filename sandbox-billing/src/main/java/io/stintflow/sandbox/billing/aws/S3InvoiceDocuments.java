package io.stintflow.sandbox.billing.aws;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceDocuments;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * SDD S.2, 8a: invoice documents in the shared {@code sandbox-facts} bucket, under the prefix only Billing writes
 * ({@code billing/invoices/}, see the bucket policy in {@code contracts/events.md}).
 */
public final class S3InvoiceDocuments implements InvoiceDocuments {

    public static final String PREFIX = "billing/invoices/";

    private final S3Client s3;
    private final String bucket;

    public S3InvoiceDocuments(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** The document's key: a function of the invoice id, so a repeated render overwrites the same object. */
    public static String key(String invoiceId) {
        return PREFIX + invoiceId + ".html";
    }

    @Override
    public URI put(String invoiceId, String html) {
        String key = key(invoiceId);
        s3.putObject(r -> r.bucket(bucket).key(key).contentType("text/html; charset=utf-8"),
                RequestBody.fromString(html, StandardCharsets.UTF_8));
        return URI.create("s3://" + bucket + "/" + key);
    }
}
