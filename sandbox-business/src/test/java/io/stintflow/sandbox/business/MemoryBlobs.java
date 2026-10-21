package io.stintflow.sandbox.business;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.stintflow.spi.BlobNotFoundException;
import io.stintflow.spi.BlobStore;

/** Test support: blobs in memory under {@code s3://sandbox-test/<key>}, the pointer form the contracts allow. */
final class MemoryBlobs implements BlobStore {

    final Map<URI, byte[]> data = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<URI> put(byte[] bytes, String key) {
        URI uri = URI.create("s3://sandbox-test/" + key);
        data.put(uri, bytes);
        return CompletableFuture.completedFuture(uri);
    }

    @Override
    public CompletionStage<byte[]> get(URI ref) {
        byte[] bytes = data.get(ref);
        return bytes == null ? CompletableFuture.failedFuture(new BlobNotFoundException(ref, "not in memory"))
                : CompletableFuture.completedFuture(bytes);
    }

    @Override
    public CompletionStage<Void> delete(URI ref) {
        data.remove(ref);
        return CompletableFuture.completedFuture(null);
    }
}
