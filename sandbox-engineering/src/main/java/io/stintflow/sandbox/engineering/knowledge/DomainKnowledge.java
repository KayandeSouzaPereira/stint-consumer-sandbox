package io.stintflow.sandbox.engineering.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.model.chat.ChatModel;
import io.stintflow.knowledge.DeterministicEmbedder;
import io.stintflow.knowledge.Embedder;
import io.stintflow.knowledge.KnowledgeScope;
import io.stintflow.knowledge.extraction.Glossary;
import io.stintflow.knowledge.inmemory.CompositeKnowledgeStore;
import io.stintflow.knowledge.inspector.InspectorAccess;
import io.stintflow.knowledge.inspector.InspectorServer;
import io.stintflow.knowledge.inspector.InspectorService;
import io.stintflow.spi.AuditLog;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.RetentionClass;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.decision.SensitivityPolicy;
import io.stintflow.worker.knowledge.KnowledgeWorkers;
import io.stintflow.worker.knowledge.ScopeAllowList;
import io.stintflow.worker.langchain4j.KnowledgeExtractionWorker;
import io.stintflow.worker.redaction.Pseudonyms;

/**
 * SDD S.12, RF1: the knowledge base of one domain. It owns its store (in memory: Stint 3b has no other adapter, finding
 * S12-1), serves only its own scope (a task naming another scope gets a 403) and registers the knowledge tasks on the
 * domain's worker: {@code extract-knowledge}, {@code knowledge-write}, {@code knowledge-retrieve},
 * {@code knowledge-erase}, {@code knowledge-verify}, {@code resolve-person} and, when given a target embedder,
 * {@code knowledge-reindex}. The inspector, read-only and closed to everybody that is not on the operators' list, reads
 * the same store.
 */
public final class DomainKnowledge implements AutoCloseable {

    public static final String SCHEMA = "meetings@1";
    /** A synthetic secret, the same the privacy domain uses: stable pseudonyms must agree between the two. */
    static final byte[] SYNTHETIC_SECRET = "sandbox-privacy-test-secret-not-for-real-data"
            .getBytes(StandardCharsets.UTF_8);
    public static final RetentionClass BATCH = new RetentionClass("knowledge-batch", Duration.ofDays(7));
    public static final RetentionClass RESTRICTED = new RetentionClass("restricted", Duration.ofDays(30));

    private final KnowledgeScope scope;
    private final CompositeKnowledgeStore store;
    private final BlobStore blobs;
    private final Glossary glossary;
    private final ChatModel model;
    private final Embedder reindexTarget;
    private final InstantSource clock;
    private InspectorServer inspector;

    public DomainKnowledge(KnowledgeScope scope, Glossary glossary, BlobStore blobs, ChatModel model,
            Embedder reindexTarget, InstantSource clock) {
        this.scope = scope;
        this.glossary = glossary;
        this.blobs = blobs;
        this.model = model != null ? model : new ScriptedExtraction(glossary);
        this.reindexTarget = reindexTarget;
        this.clock = clock;
        this.store = new CompositeKnowledgeStore(new DeterministicEmbedder(), blobs, RESTRICTED, clock);
    }

    public KnowledgeScope scope() {
        return scope;
    }

    public CompositeKnowledgeStore store() {
        return store;
    }

    public Glossary glossary() {
        return glossary;
    }

    /** Adds the knowledge routes to the domain worker's registry. */
    public TaskHandlerRegistry register(TaskHandlerRegistry registry, AuditLog audit) {
        ScopeAllowList scopes = new ScopeAllowList(Set.of(scope));
        registry.register("extract-knowledge", KnowledgeExtractionWorker.builder().chatModel(model)
                .provider("sandbox", false).policy(SensitivityPolicy.fromProperties(Map.of()))
                .glossaries(ref -> ref.equals(glossary.id() + "@" + glossary.version()) ? glossary : null)
                .store(store).blobs(blobs, BATCH).build());
        KnowledgeWorkers.register(registry, store, blobs, scopes, audit, clock);
        return KnowledgeWorkers.registerErasure(registry, store, blobs, scopes, audit, clock, reindexTarget,
                new Pseudonyms(SYNTHETIC_SECRET));
    }

    /**
     * Starts the read-only inspector on {@code port}. {@code operators} are the callers who may look at this base; with
     * none, nobody can (the default is to refuse).
     */
    public synchronized InspectorServer startInspector(int port, List<String> operators) throws IOException {
        InspectorAccess access = operators.isEmpty() ? InspectorAccess.none()
                : InspectorAccess.allowing(operators.stream().collect(java.util.stream.Collectors.toMap(o -> o,
                        o -> Set.of(scope))));
        inspector = new InspectorServer(new InspectorService(store, access, InspectorService.Settings.DEFAULT),
                List.of(scope), InspectorServer.operatorHeader(), port).start();
        return inspector;
    }

    @Override
    public synchronized void close() {
        if (inspector != null) {
            inspector.close();
        }
    }
}
