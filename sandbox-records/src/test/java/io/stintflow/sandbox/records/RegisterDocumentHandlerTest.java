package io.stintflow.sandbox.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.spi.BlobStore;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;

/** SDD S.5, 8c: the register keeps one version per content, and answers pointers and ids only. */
class RegisterDocumentHandlerTest {

    static final class MapBlobs implements BlobStore {
        final Map<URI, byte[]> data = new ConcurrentHashMap<>();

        @Override
        public CompletionStage<URI> put(byte[] bytes, String key) {
            URI uri = URI.create("s3://b/" + key);
            data.put(uri, bytes);
            return CompletableFuture.completedFuture(uri);
        }

        @Override
        public CompletionStage<byte[]> get(URI ref) {
            return CompletableFuture.completedFuture(data.get(ref));
        }

        @Override
        public CompletionStage<Void> delete(URI ref) {
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class ListCatalog implements RegisterDocumentHandler.Catalog {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public synchronized Optional<Entry> latest(String recordId) {
            return entries.stream().filter(e -> e.recordId().equals(recordId)).reduce((a, b) -> b);
        }

        @Override
        public synchronized boolean putIfAbsent(Entry entry) {
            return entries.stream().noneMatch(e -> e.recordId().equals(entry.recordId())
                    && e.version() == entry.version()) && entries.add(entry);
        }
    }

    final MapBlobs blobs = new MapBlobs();
    final ListCatalog catalog = new ListCatalog();
    final RegisterDocumentHandler handler = new RegisterDocumentHandler(catalog, blobs, blobs);

    JsonNode register(URI ref, String kind) {
        var input = Json.obj().put("documentId", "p1-1-design").put("documentRef", ref.toString()).put("kind", kind)
                .put("ownerRole", "business-rep").put("sensitivity", "confidential").put("projectId", "p1")
                .put("cycle", "1");
        return handler.execute(new TaskContext("/do/7/register", "i-1", "c-1", 1, input,
                new WorkflowRef("business", "business-design", "1.0.0"), "chn-1", Lineage.NONE))
                .toCompletableFuture().join();
    }

    @Test
    void the_same_content_is_the_same_version_and_new_content_the_next() {
        URI v1 = blobs.put("# Design\nsearch by author".getBytes(StandardCharsets.UTF_8), "d1.md").toCompletableFuture()
                .join();
        JsonNode first = register(v1, "design");
        JsonNode again = register(v1, "design");
        URI v2 = blobs.put("# Design\nsearch by author and ISBN".getBytes(StandardCharsets.UTF_8), "d2.md")
                .toCompletableFuture().join();
        JsonNode second = register(v2, "design");

        assertThat(first.path("recordId").asText()).isEqualTo("rec-p1-1-design");
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(again).isEqualTo(first);
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(second.path("recordRef").asText()).isEqualTo("record://rec-p1-1-design/2");
        assertThat(catalog.entries).hasSize(2);
        assertThat(blobs.data).containsKey(URI.create("s3://b/rec-p1-1-design/v2"));
        assertThat(first.toString()).doesNotContain("search by author");
    }

    @Test
    void an_unknown_kind_is_a_validation_failure() {
        URI v1 = blobs.put("x".getBytes(StandardCharsets.UTF_8), "d1.md").toCompletableFuture().join();
        assertThatThrownBy(() -> register(v1, "poem")).hasMessageContaining("known kind");
    }
}
