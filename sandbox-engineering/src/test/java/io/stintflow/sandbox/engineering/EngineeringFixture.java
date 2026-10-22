package io.stintflow.sandbox.engineering;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionHandler;
import io.stintflow.worker.redaction.TermListLayer;

/**
 * Test support (SDD S.9): the Engineering domain's CI models with stand-ins of the shared services that remember what
 * they were asked, so a test can say which artifact each path produced. The register keeps versions like the real one
 * (the same content is the same version, new content the next, which {@code supersedes} the one before it).
 */
final class EngineeringFixture {

    static final Corpus CORPUS = EngineeringWorkflowsTest.CORPUS;

    /** What a service was asked: the route and its input. */
    record Call(String route, JsonNode input) {
    }

    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> versions = new HashMap<>();

    List<Call> of(String route) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.route().equals(route)).toList();
        }
    }

    /** The registrations of one kind, in order. */
    List<JsonNode> registered(String kind) {
        return of("register-document").stream().map(Call::input).filter(i -> kind.equals(i.path("kind").asText()))
                .toList();
    }

    TaskHandlerRegistry handlers(BlobStore blobs) {
        List<String> markers = CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker)
                .toList();
        return EngineeringEngine.handlers(CORPUS, EngineeringModels.fakes(CORPUS), blobs, blobs)
                .register("redactor", new RedactionHandler(new LayeredRedactor("test", "1", List.of(new PatternLayer(),
                        new TermListLayer("person", markers))), new Pseudonyms("test-secret-0123456789"
                                .getBytes(StandardCharsets.UTF_8)), blobs))
                .register("write-sdds", spying("write-sdds", EngineeringWriters.sdds(CORPUS, blobs)))
                .register("finalize-batch", spying("finalize-batch", EngineeringWriters.finalizeBatch(CORPUS, blobs)))
                .register("write-timeout-report", spying("write-timeout-report", new TimeoutReportHandler(blobs)))
                .register("register-document", recording("register-document", ctx -> register(blobs, ctx.input())))
                .register("send-email", recording("send-email", ctx -> Json.obj().put("sent", true)));
    }

    private TaskHandler spying(String route, TaskHandler delegate) {
        return ctx -> {
            calls.add(new Call(route, ctx.input()));
            return delegate.execute(ctx);
        };
    }

    private TaskHandler recording(String route, Function<TaskContext, JsonNode> answer) {
        return ctx -> {
            calls.add(new Call(route, ctx.input()));
            return CompletableFuture.completedFuture(answer.apply(ctx));
        };
    }

    private synchronized JsonNode register(BlobStore blobs, JsonNode in) {
        String documentId = in.path("documentId").asText();
        String sha = Corpus.sha256(blobs.get(URI.create(in.path("documentRef").asText())).toCompletableFuture().join());
        List<String> seen = versions.computeIfAbsent(documentId, k -> new ArrayList<>());
        boolean again = !seen.isEmpty() && seen.getLast().equals(sha);
        if (!again) {
            seen.add(sha);
        }
        ObjectNode out = Json.obj().put("recordId", "rec-" + documentId).put("version", seen.size())
                .put("sha256", sha).put("recordRef", "record://rec-" + documentId + "/" + seen.size());
        if (seen.size() > 1) {
            out.put("supersedes", seen.size() - 1);
        }
        return out;
    }
}
