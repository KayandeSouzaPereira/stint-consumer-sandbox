package io.stintflow.sandbox.engineering.knowledge;

import java.io.IOException;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import io.stintflow.knowledge.DeterministicEmbedder;
import io.stintflow.knowledge.KnowledgeScope;
import io.stintflow.knowledge.inmemory.CompositeKnowledgeStore;
import io.stintflow.knowledge.inspector.InspectorAccess;
import io.stintflow.knowledge.inspector.InspectorServer;
import io.stintflow.knowledge.inspector.InspectorService;
import io.stintflow.spi.AuditLog;
import io.stintflow.spi.BlobStore;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.knowledge.KnowledgeWorkers;
import io.stintflow.worker.knowledge.OriginAllowList;
import io.stintflow.worker.knowledge.ScopeAllowList;

/**
 * SDD S.14, 8b: the architecture base of Engineering, apart from its knowledge base ({@code engineering-kb}): the pointers
 * to what Business decided (a {@code DomainReference} node holds the pointer and nothing of the node it points at) and,
 * later, the ADRs. It listens to announcements from the Business base and from no other.
 */
public final class ArchitectureBase implements AutoCloseable {

    public static final KnowledgeScope SCOPE = KnowledgeScope.named("sandbox", "engineering-arch");
    public static final KnowledgeScope BUSINESS = KnowledgeScope.named("sandbox", "business-kb");

    private final CompositeKnowledgeStore store;
    private final InstantSource clock;
    private InspectorServer inspector;

    public ArchitectureBase(BlobStore blobs, InstantSource clock) {
        this.clock = clock;
        this.store = new CompositeKnowledgeStore(new DeterministicEmbedder(), blobs, DomainKnowledge.RESTRICTED, clock);
    }

    public CompositeKnowledgeStore store() {
        return store;
    }

    /** {@code knowledge-reference}: the references to the nodes the Business base announces, written in this base only. */
    public TaskHandlerRegistry register(TaskHandlerRegistry registry, AuditLog audit) {
        return KnowledgeWorkers.registerReference(registry, store, new ScopeAllowList(Set.of(SCOPE)),
                new OriginAllowList(Set.of(BUSINESS)), audit, clock);
    }

    public synchronized InspectorServer startInspector(int port, List<String> operators) throws IOException {
        InspectorAccess access = operators.isEmpty() ? InspectorAccess.none()
                : InspectorAccess.allowing(operators.stream().collect(java.util.stream.Collectors.toMap(o -> o,
                        o -> Set.of(SCOPE))));
        inspector = new InspectorServer(new InspectorService(store, access, InspectorService.Settings.DEFAULT),
                List.of(SCOPE), InspectorServer.operatorHeader(), port).start();
        return inspector;
    }

    @Override
    public synchronized void close() {
        if (inspector != null) {
            inspector.close();
        }
    }
}
