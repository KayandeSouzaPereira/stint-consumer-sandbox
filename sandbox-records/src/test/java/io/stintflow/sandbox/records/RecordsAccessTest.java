package io.stintflow.sandbox.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;

/**
 * SDD S.7, CA2 to CA5: two registrations of the same content at the same instant make one version; an approval lands
 * on the right version, once per review, and leaves the document alone; only the owner domain reads a record; the
 * event carries pointers and ids, never content.
 */
class RecordsAccessTest {

    final RegisterDocumentHandlerTest.MapBlobs blobs = new RegisterDocumentHandlerTest.MapBlobs();
    final RegisterDocumentHandlerTest.ListCatalog catalog = new RegisterDocumentHandlerTest.ListCatalog();

    static TaskContext task(ObjectNode input, String domain) {
        return new TaskContext("/do/7/x", "i-1", "c-1", 1, input, new WorkflowRef(domain, domain + "-flow", "1.0.0"),
                "chn-1", new Lineage("chn-1", "evt-1", null, null));
    }

    ObjectNode registration(URI ref) {
        return Json.obj().put("documentId", "p1-1-design").put("documentRef", ref.toString()).put("kind", "design")
                .put("ownerRole", "business-rep").put("sensitivity", "confidential").put("sourceMeetingId", "m-1");
    }

    URI doc(String text) {
        return blobs.put(text.getBytes(StandardCharsets.UTF_8), "d-" + blobs.data.size() + ".md").toCompletableFuture()
                .join();
    }

    /** A catalog that holds both callers at their read, so both see "no version yet" and both try to write the first. */
    static final class RacingCatalog implements RegisterDocumentHandler.Catalog {
        final RegisterDocumentHandlerTest.ListCatalog inner;
        final CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();

        RacingCatalog(RegisterDocumentHandlerTest.ListCatalog inner) {
            this.inner = inner;
        }

        @Override
        public Optional<Entry> latest(String recordId) {
            Optional<Entry> seen = inner.latest(recordId);
            if (reads.incrementAndGet() <= 2) {   // only the first read of each caller waits for the other
                try {
                    bothHaveRead.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return seen;
        }

        @Override
        public Optional<Entry> get(String recordId, int version) {
            return inner.get(recordId, version);
        }

        @Override
        public boolean putIfAbsent(Entry entry) {
            return inner.putIfAbsent(entry);
        }
    }

    @Test
    void two_registrations_of_the_same_content_at_once_make_one_version() {
        var racing = new RacingCatalog(catalog);
        var handler = new RegisterDocumentHandler(racing, blobs, blobs);
        URI ref = doc("the same design");
        CompletableFuture<JsonNode> a = CompletableFuture.supplyAsync(
                () -> handler.execute(task(registration(ref), "business")).toCompletableFuture().join());
        CompletableFuture<JsonNode> b = CompletableFuture.supplyAsync(
                () -> handler.execute(task(registration(ref), "business")).toCompletableFuture().join());

        assertThat(a.join().path("version").asInt()).isEqualTo(1);
        assertThat(b.join()).isEqualTo(a.join());
        assertThat(catalog.entries).hasSize(1);
    }

    @Test
    void an_approval_lands_on_its_version_once_per_review_and_leaves_the_document_alone() {
        var register = new RegisterDocumentHandler(catalog, blobs, blobs);
        register.execute(task(registration(doc("v1")), "business")).toCompletableFuture().join();
        register.execute(task(registration(doc("v2")), "business")).toCompletableFuture().join();
        var before = new ArrayList<>(catalog.entries);
        var approvals = new InMemoryApprovals();
        var approve = new ApproveRecordHandler(catalog, approvals);

        ObjectNode rejection = Json.obj().put("recordId", "rec-p1-1-design").put("version", 1).put("reviewId", "rev-1")
                .put("approver", "business-rep").put("decision", "rejected").put("note", "needs the search by ISBN");
        approve.execute(task(rejection, "business")).toCompletableFuture().join();
        ObjectNode approval = Json.obj().put("recordId", "rec-p1-1-design").put("version", 2).put("reviewId", "rev-2")
                .put("approver", "business-rep").put("decision", "approved");
        JsonNode answer = approve.execute(task(approval, "business")).toCompletableFuture().join();
        JsonNode again = approve.execute(task(approval, "business")).toCompletableFuture().join();

        assertThat(approvals.of("rec-p1-1-design", 1)).extracting(a -> a.decision()).containsExactly("rejected");
        assertThat(approvals.of("rec-p1-1-design", 2)).hasSize(1);
        assertThat(again).isEqualTo(answer);
        assertThat(answer.toString()).doesNotContain("ISBN");
        assertThat(catalog.entries).isEqualTo(before);
        // the same review deciding something else is a conflict; an unknown version is not found
        ObjectNode flip = approval.deepCopy().put("decision", "rejected");
        assertThatThrownBy(() -> approve.execute(task(flip, "business")).toCompletableFuture().join())
                .hasMessageContaining("already decided");
        ObjectNode ghost = approval.deepCopy().put("version", 9).put("reviewId", "rev-3");
        assertThatThrownBy(() -> approve.execute(task(ghost, "business")).toCompletableFuture().join())
                .hasMessageContaining("no such record version");
    }

    @Test
    void only_the_owner_domain_reads_a_record_and_the_answer_is_a_pointer() {
        var register = new RegisterDocumentHandler(catalog, blobs, blobs);
        register.execute(task(registration(doc("the secret-free design text")), "business")).toCompletableFuture().join();
        var read = new ReadRecordHandler(catalog);
        ObjectNode ask = Json.obj().put("recordId", "rec-p1-1-design");

        JsonNode owner = read.execute(task(ask, "business")).toCompletableFuture().join();
        assertThat(owner.path("contentRef").asText()).isEqualTo("s3://b/rec-p1-1-design/v1");
        assertThat(owner.toString()).doesNotContain("design text");
        for (String other : List.of("engineering", "delivery")) {
            assertThatThrownBy(() -> read.execute(task(ask, other)).toCompletableFuture().join())
                    .hasMessageContaining("another domain");
        }
        assertThatThrownBy(() -> read.execute(task(Json.obj().put("recordId", "rec-none"), "business"))
                .toCompletableFuture().join()).hasMessageContaining("no such record");
    }

    @Test
    void the_event_has_ids_and_pointers_only_and_content_is_refused() {
        var contracts = new PipelineContracts();
        String file = "io.sandbox.records.document-registered.v1.schema.json";
        ObjectNode event = Json.obj().put("recordId", "rec-p1-1-design").put("version", 2).put("sha256", "a".repeat(64))
                .put("kind", "design").put("sensitivity", "confidential").put("ownerRole", "business-rep")
                .put("supersedes", 1);
        assertThat(contracts.dataViolations(file, event)).isEmpty();
        for (String field : List.of("text", "content", "body", "document", "transcript", "note")) {
            assertThat(contracts.dataViolations(file, event.deepCopy().put(field, "anything")))
                    .as("a '" + field + "' field").isNotEmpty();
        }
    }

    /** The approvals of the tests: what the table keeps, by (record, version, review). */
    static final class InMemoryApprovals implements ApproveRecordHandler.Approvals {
        final List<Approval> all = new ArrayList<>();

        @Override
        public synchronized Optional<Approval> putIfAbsent(Approval approval) {
            Optional<Approval> same = all.stream().filter(a -> a.recordId().equals(approval.recordId())
                    && a.version() == approval.version() && a.reviewId().equals(approval.reviewId())).findFirst();
            if (same.isEmpty()) {
                all.add(approval);
            }
            return same;
        }

        @Override
        public synchronized List<Approval> of(String recordId, int version) {
            return all.stream().filter(a -> a.recordId().equals(recordId) && a.version() == version).toList();
        }
    }
}
