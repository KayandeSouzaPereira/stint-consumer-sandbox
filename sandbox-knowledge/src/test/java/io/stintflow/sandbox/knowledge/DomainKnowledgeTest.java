package io.stintflow.sandbox.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.InstantSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.inmemory.FilesystemBlobStore;
import io.stintflow.inmemory.InMemoryAuditLog;
import io.stintflow.knowledge.KnowledgeScope;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandlerRegistry;

/** SDD S.12, RF1 and RF3: a domain's base takes a document by pointer, answers with origin, and serves no other scope. */
class DomainKnowledgeTest {

    static final KnowledgeScope SCOPE = KnowledgeScope.named("sandbox", "business-kb");
    static final WorkflowRef WORKFLOW = new WorkflowRef("business", "knowledge-ingest", "1.0.0");

    @TempDir
    Path dir;

    private JsonNode run(TaskHandlerRegistry registry, String route, ObjectNode input) {
        return registry.find(route).orElseThrow().execute(new TaskContext("/do/" + route, "i1", "c-" + route, 1, input,
                WORKFLOW, "chn-1", Lineage.NONE)).toCompletableFuture().join();
    }

    private ObjectNode scoped(KnowledgeScope scope) {
        ObjectNode in = Json.obj();
        in.putObject("scope").put("namespace", scope.namespace()).put("base", scope.base());
        return in;
    }

    @Test
    void a_document_is_ingested_by_pointer_and_found_again_with_its_origin() throws Exception {
        BlobStore blobs = new FilesystemBlobStore(dir);
        DomainKnowledge kb = new DomainKnowledge(SCOPE, Glossaries.business(), blobs, null, null, InstantSource.system());
        TaskHandlerRegistry registry = kb.register(new TaskHandlerRegistry(), new InMemoryAuditLog());
        var text = blobs.put("Members may borrow books. A late return blocks new loans.".getBytes(StandardCharsets.UTF_8),
                "docs/d1").toCompletableFuture().join();

        ObjectNode extract = scoped(SCOPE);
        extract.putObject("meeting").put("meetingId", "d1");
        extract.put("textRef", text.toString()).put("schema", DomainKnowledge.SCHEMA).put("glossary", "business@1")
                .put("revision", 1);
        JsonNode batch = run(registry, "extract-knowledge", extract);
        ObjectNode write = scoped(SCOPE);
        write.put("batchRef", batch.path("batchRef").asText());
        assertThat(run(registry, "knowledge-write", write).path("status").asText()).isIn("APPLIED", "UNCHANGED");
        assertThat(run(registry, "knowledge-write", write).path("status").asText()).isEqualTo("UNCHANGED");

        ObjectNode ask = scoped(SCOPE);
        ask.put("query", "late return").put("topK", 3);
        JsonNode found = run(registry, "knowledge-retrieve", ask);
        assertThat(found.path("items")).isNotEmpty();
        assertThat(found.path("items").get(0).path("origin").toString()).contains("d1");
    }

    @Test
    void another_domains_scope_is_refused() throws Exception {
        BlobStore blobs = new FilesystemBlobStore(dir);
        DomainKnowledge kb = new DomainKnowledge(SCOPE, Glossaries.business(), blobs, null, null, InstantSource.system());
        TaskHandlerRegistry registry = kb.register(new TaskHandlerRegistry(), new InMemoryAuditLog());
        ObjectNode ask = scoped(KnowledgeScope.named("sandbox", "engineering-kb"));
        ask.put("query", "anything");
        assertThatThrownBy(() -> run(registry, "knowledge-retrieve", ask)).hasCauseInstanceOf(TaskFailure.class)
                .cause().satisfies(e -> assertThat(((TaskFailure) e).toErrorInfo().status()).isEqualTo(403));
    }
}
