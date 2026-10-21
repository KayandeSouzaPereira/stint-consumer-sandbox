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
        public synchronized Optional<Entry> get(String recordId, int version) {
            return entries.stream().filter(e -> e.recordId().equals(recordId) && e.version() == version).findFirst();
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
                .put("cycle", "1").put("sourceMeetingId", "m-1");
        return register(input);
    }

    JsonNode register(com.fasterxml.jackson.databind.node.ObjectNode input) {
        return handler.execute(new TaskContext("/do/7/register", "i-1", "c-1", 1, input,
                new WorkflowRef("business", "business-design", "1.0.0"), "chn-1",
                new Lineage("chn-1", "evt-9", null, null))).toCompletableFuture().join();
    }

    URI doc(String text) {
        return blobs.put(text.getBytes(StandardCharsets.UTF_8), "d-" + blobs.data.size() + ".md").toCompletableFuture()
                .join();
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

    @Test
    void an_entry_has_its_origin_its_chain_and_the_version_it_supersedes() {
        register(doc("v1 text"), "design");
        JsonNode second = register(doc("v2 text"), "design");

        var v1 = catalog.get("rec-p1-1-design", 1).orElseThrow();
        var v2 = catalog.get("rec-p1-1-design", 2).orElseThrow();
        assertThat(v1.supersedes()).isNull();
        assertThat(v2.supersedes()).isEqualTo(1);
        assertThat(second.path("supersedes").asInt()).isEqualTo(1);
        assertThat(v2.sourceMeetingId()).isEqualTo("m-1");
        assertThat(v2.chainId()).isEqualTo("chn-1");
        assertThat(v2.causationId()).isEqualTo("evt-9");
        assertThat(v2.retentionClass()).isEqualTo("records");
        assertThat(v1.sha256()).isNotEqualTo(v2.sha256());   // the first version did not change
        assertThat(new String(blobs.data.get(URI.create(v1.storedRef())), StandardCharsets.UTF_8)).isEqualTo("v1 text");
    }

    @Test
    void the_last_content_again_is_no_new_version_but_an_older_content_after_a_newer_one_is() {
        URI a = doc("content A");
        URI b = doc("content B");
        register(a, "design");
        register(b, "design");
        JsonNode third = register(a, "design");   // A, B, A

        assertThat(third.path("version").asInt()).isEqualTo(3);
        assertThat(third.path("supersedes").asInt()).isEqualTo(2);
        assertThat(register(a, "design").path("version").asInt()).isEqualTo(3);   // a redelivery of the last
        assertThat(catalog.entries).hasSize(3);
    }

    @Test
    void a_document_of_a_meeting_names_the_meeting_and_the_new_kinds_are_known() {
        URI ref = doc("x");
        var noMeeting = Json.obj().put("documentId", "d").put("documentRef", ref.toString()).put("kind", "design")
                .put("ownerRole", "business-rep").put("sensitivity", "confidential");
        assertThatThrownBy(() -> register(noMeeting)).hasMessageContaining("sourceMeetingId");
        for (String kind : List.of("email", "page", "patch", "dlq-decision")) {
            var in = Json.obj().put("documentId", "d-" + kind).put("documentRef", ref.toString()).put("kind", kind)
                    .put("ownerRole", "tech-rep").put("sensitivity", "internal");
            assertThat(register(in).path("kind").asText()).isEqualTo(kind);
        }
        var redacted = Json.obj().put("documentId", "t-1").put("documentRef", ref.toString())
                .put("kind", "transcript-redacted").put("ownerRole", "business-rep").put("sensitivity", "confidential")
                .put("sourceMeetingId", "m-1");
        assertThat(register(redacted).path("kind").asText()).isEqualTo("transcript-redacted");
    }

    @Test
    void a_pointer_to_a_restricted_bucket_an_unknown_level_and_an_unknown_role_are_refused() {
        var raw = Json.obj().put("documentId", "d").put("documentRef", "s3://sandbox-meetings-raw/audio/m-1.wav")
                .put("kind", "design").put("ownerRole", "business-rep").put("sensitivity", "confidential")
                .put("sourceMeetingId", "m-1");
        assertThatThrownBy(() -> register(raw)).hasMessageContaining("restricted bucket");
        URI ref = doc("x");
        var level = Json.obj().put("documentId", "d").put("documentRef", ref.toString()).put("kind", "page")
                .put("ownerRole", "tech-rep").put("sensitivity", "secret");
        assertThatThrownBy(() -> register(level)).hasMessageContaining("known kind");
        var role = Json.obj().put("documentId", "d").put("documentRef", ref.toString()).put("kind", "page")
                .put("ownerRole", "intern").put("sensitivity", "internal");
        assertThatThrownBy(() -> register(role)).hasMessageContaining("known kind");
    }
}
