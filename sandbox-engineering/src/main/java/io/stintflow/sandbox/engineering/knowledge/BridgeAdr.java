package io.stintflow.sandbox.engineering.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.spi.AuditLog;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.RetentionClass;
import io.stintflow.wire.Json;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.decision.Decision;
import io.stintflow.worker.decision.DecisionConfig;
import io.stintflow.worker.decision.DecisionHandlers;
import io.stintflow.worker.decision.FakeDecisionAdapter;
import io.stintflow.worker.decision.SensitivityPolicy;
import io.stintflow.worker.knowledge.KnowledgeWorkers;
import io.stintflow.worker.knowledge.OriginAllowList;
import io.stintflow.worker.knowledge.ScopeAllowList;

/**
 * SDD S.14, RF7 and RF8: what the Engineering worker adds for the ADR — the tasks of Stint 3c.4 over the architecture base
 * (pair the drafted decisions with the pointers that motivate them, put each pair's material in a short-lived blob, write
 * the ADRs), the decision that judges a pair, and the questions to Business when an ADR still conflicts. The decision of the
 * build reads its verdict from the answer key of the corpus (the local model is the manual run).
 */
public final class BridgeAdr {

    public static final RetentionClass MATERIAL = new RetentionClass("adr-material", Duration.ofDays(1));
    public static final RetentionClass DOCUMENT = new RetentionClass("adr-document", Duration.ofDays(30));
    public static final String CONFLICT_QUESTIONS = "write-conflict-questions";

    private BridgeAdr() {
    }

    public static TaskHandlerRegistry register(TaskHandlerRegistry registry, ArchitectureBase base, BlobStore blobs,
            KnowledgeCorpus answerKey, AuditLog audit, InstantSource clock) {
        KnowledgeWorkers.registerAdrs(registry, base.store(), blobs, new ScopeAllowList(Set.of(ArchitectureBase.SCOPE)),
                new OriginAllowList(Set.of(ArchitectureBase.BUSINESS)), MATERIAL, DOCUMENT, audit, clock);
        FakeDecisionAdapter conflict = conflictRules(blobs, answerKey);
        DecisionHandlers.builder(DecisionConfig.fromProperties(Map.of(
                        "stint.decision.routes.decide-conflict.adapter", conflict.descriptor().id(),
                        "stint.decision.routes.decide-conflict.instructions",
                        "Does the architecture decision contradict the business decision, or make it impossible?")))
                .adapter(conflict).policy(SensitivityPolicy.fromProperties(Map.of())).build().registerAll(registry);
        return registry.register(CONFLICT_QUESTIONS, conflictQuestions(blobs));
    }

    /** The verdict the answer key gives the draft whose summary this pair carries; a pair the key does not know is uncertain. */
    static FakeDecisionAdapter conflictRules(BlobStore blobs, KnowledgeCorpus answerKey) {
        return new FakeDecisionAdapter("sandbox-conflict-rules", request -> {
            JsonNode material = Json.read(blobs.get(java.net.URI.create(request.input().path("materialRef").asText()))
                    .toCompletableFuture().join());
            String summary = material.path("architecture").path("summary").asText().toLowerCase(Locale.ROOT);
            for (KnowledgeCorpus.Architecture session : answerKey.architecture()) {
                for (KnowledgeCorpus.Draft draft : session.drafts()) {
                    if (draft.summary().toLowerCase(Locale.ROOT).equals(summary)) {
                        return Decision.of(TextNode.valueOf(draft.verdict()), 0.9);
                    }
                }
            }
            return Decision.of(TextNode.valueOf("compatible"), 0.3);
        });
    }

    /**
     * {@code write-conflict-questions}: the questions Engineering sends Business for the ADRs still in conflict. Input
     * {@code {projectId, cycle, adrs: [{decisionId, title, status}]}}; output {@code {documentId, questionsRef,
     * questionCount}}. The text names the ADRs, never what Business decided.
     */
    static io.stintflow.worker.TaskHandler conflictQuestions(BlobStore documents) {
        return ctx -> {
            JsonNode in = ctx.input();
            String documentId = in.path("projectId").asText() + "-c" + in.path("cycle").asText() + "-adr-questions";
            List<String> lines = new ArrayList<>();
            for (JsonNode adr : in.path("adrs")) {
                if ("conflicting".equals(adr.path("status").asText())) {
                    lines.add("The architecture decision \"" + adr.path("title").asText() + "\" (" + adr.path("decisionId").asText()
                            + ") conflicts with what Business decided. Which one holds?");
                }
            }
            StringBuilder md = new StringBuilder("# Questions on the architecture decisions (").append(documentId).append(")\n\n");
            for (int i = 0; i < lines.size(); i++) {
                md.append(i + 1).append(". ").append(lines.get(i)).append('\n');
            }
            return documents.put(md.toString().getBytes(StandardCharsets.UTF_8), "questions/" + documentId + ".md")
                    .thenApply(uri -> (JsonNode) Json.obj().put("documentId", documentId).put("questionsRef", uri.toString())
                            .put("questionCount", lines.size()));
        };
    }

}
