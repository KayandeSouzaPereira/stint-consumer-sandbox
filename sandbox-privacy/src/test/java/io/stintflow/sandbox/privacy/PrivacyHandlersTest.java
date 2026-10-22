package io.stintflow.sandbox.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.ClaimCheck;
import io.stintflow.wire.Json;
import io.stintflow.wire.redaction.RedactionContract;
import io.stintflow.worker.TaskContext;

/**
 * SDD S.5, 8c and S.6: the privacy service's filter, over every meeting of the corpus as the fake transcriber writes
 * it: no written marker survives, and since S.6 no spoken form does either (the reference redactor let them through:
 * Stint 3a.6 finding).
 */
class PrivacyHandlersTest {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));

    static final class MapBlobs implements BlobStore {
        final Map<URI, byte[]> data = new ConcurrentHashMap<>();

        @Override
        public CompletionStage<URI> put(byte[] bytes, String key) {
            URI uri = URI.create("mem://b/" + key);
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

    @Test
    void no_written_marker_of_the_corpus_survives_the_reference_redactor() {
        MapBlobs blobs = new MapBlobs();
        var handler = PrivacyWorker.handlers(CORPUS, blobs).find(PrivacyWorker.ROUTE).orElseThrow();
        for (Corpus.Meeting meeting : CORPUS.meetings()) {
            ObjectNode transcript = Json.obj().put("language", "en");
            ArrayNode segments = transcript.putArray("segments");
            meeting.script().forEach(t -> segments.add(Json.obj().put("start", 0.0).put("end", 1.0)
                    .put("text", t.text())));
            URI ref = blobs.put(Json.bytes(transcript), "raw/" + meeting.id() + ".json").toCompletableFuture().join();
            ObjectNode request = Json.obj();
            request.set(RedactionContract.INPUT, ClaimCheck.pointer(ref));
            request.putArray(RedactionContract.TYPES);

            JsonNode result = handler.execute(new TaskContext("/do/2/redact", "i-" + meeting.id(), "c-1", 1, request,
                    new WorkflowRef("business", "business-design", "1.0.0"), "chn-1", Lineage.NONE))
                    .toCompletableFuture().join();

            assertThat(RedactionContract.violations(result)).as(meeting.id()).isEmpty();
            assertThat(CORPUS.leaksIn(result.path("output").asText())).as(meeting.id()).isEmpty();
        }
    }

    @Test
    void spoken_forms_no_longer_escape_the_layered_filter() {
        var handler = PrivacyWorker.handlers(CORPUS, new MapBlobs()).find(PrivacyWorker.ROUTE).orElseThrow();
        Corpus.Pii pii = CORPUS.meeting("p1-c1-business").pii().get(1);
        ObjectNode request = Json.obj().put(RedactionContract.INPUT, "Write to " + pii.spoken().get(1) + " please.");
        request.putArray(RedactionContract.TYPES);
        JsonNode result = handler.execute(new TaskContext("/do/2/redact", "i-1", "c-1", 1, request,
                new WorkflowRef("business", "business-design", "1.0.0"), "chn-1", Lineage.NONE))
                .toCompletableFuture().join();
        // S.6: the layered filter catches what Stint's reference redactor did not (3a.6 finding, S.5 CA7).
        assertThat(CORPUS.leaksIn(result.path("output").asText())).isEmpty();
    }
}
