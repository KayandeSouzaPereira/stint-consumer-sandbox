package io.stintflow.sandbox.business;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.ExecutionTracer;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionHandler;
import io.stintflow.worker.redaction.TermListLayer;

/**
 * Test support (SDD S.8): the Business domain's CI models with stand-ins of the shared services that remember what
 * they were asked, so a test can say which artifact each path produced. The register keeps versions like the real one
 * (the same content is the same version, new content the next, which {@code supersedes} the one before it).
 */
final class BusinessFixture {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));

    /** What a shared service was asked: the route and its input. */
    record Call(String route, JsonNode input) {
    }

    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> versions = new HashMap<>();

    List<Call> of(String route) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.route().equals(route)).toList();
        }
    }

    /** The domain's own routes (CI models) and recording stand-ins of privacy, records and channels. */
    TaskHandlerRegistry handlers(BlobStore blobs) {
        List<String> markers = CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker)
                .toList();
        return BusinessEngine.handlers(BusinessModels.fakes(CORPUS), blobs, blobs, ExecutionTracer.NOOP)
                .register("redactor", new RedactionHandler(new LayeredRedactor("test", "1", List.of(new PatternLayer(),
                        new TermListLayer("person", markers))), new Pseudonyms("test-secret-0123456789"
                                .getBytes(StandardCharsets.UTF_8)), blobs))
                .register("write-design", spying("write-design", DesignHandler.fake(blobs)))
                .register("write-timeout-report", spying("write-timeout-report", new TimeoutReportHandler(blobs)))
                .register("register-document", recording("register-document", ctx -> register(blobs, ctx.input())))
                .register("publish-page", recording("publish-page", ctx -> Json.obj().put("id", "page-1")
                        .put("url", "http://127.0.0.1/pages/1")))
                .register("send-email", recording("send-email", ctx -> Json.obj().put("sent", true)));
    }

    private TaskHandler spying(String route, TaskHandler delegate) {
        return ctx -> {
            calls.add(new Call(route, ctx.input()));
            return delegate.execute(ctx);
        };
    }

    private TaskHandler recording(String route, java.util.function.Function<io.stintflow.worker.TaskContext, JsonNode> answer) {
        return ctx -> {
            calls.add(new Call(route, ctx.input()));
            return CompletableFuture.completedFuture(answer.apply(ctx));
        };
    }

    private synchronized JsonNode register(BlobStore blobs, JsonNode in) {
        String documentId = in.path("documentId").asText();
        String sha = Corpus.sha256(blobs.get(java.net.URI.create(in.path("documentRef").asText()))
                .toCompletableFuture().join());
        List<String> seen = versions.computeIfAbsent(documentId, k -> new ArrayList<>());
        boolean again = !seen.isEmpty() && seen.getLast().equals(sha);
        if (!again) {
            seen.add(sha);
        }
        var out = Json.obj().put("recordId", "rec-" + documentId).put("version", seen.size()).put("sha256", sha)
                .put("recordRef", "record://rec-" + documentId + "/" + seen.size());
        if (seen.size() > 1) {
            out.put("supersedes", seen.size() - 1);
        }
        return out;
    }
}
