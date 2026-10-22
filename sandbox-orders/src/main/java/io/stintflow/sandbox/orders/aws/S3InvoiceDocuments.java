package io.stintflow.sandbox.orders.aws;

import java.net.URI;

import io.stintflow.sandbox.orders.worker.OrdersPorts.InvoiceDocuments;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * SDD S.3, RF3: one {@code GetObject} of exactly the object an invoice-issued event cites. Never a listing: the
 * {@code sandbox-facts} policy (contracts/events.md) lets Orders read only the documents it is given.
 */
public final class S3InvoiceDocuments implements InvoiceDocuments {

    private final S3Client s3;

    public S3InvoiceDocuments(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public byte[] read(URI document) {
        String bucket = document.getHost();
        String key = document.getPath().substring(1);
        return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }
}
