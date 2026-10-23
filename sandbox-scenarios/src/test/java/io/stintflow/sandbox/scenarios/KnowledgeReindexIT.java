package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.KnowledgePlant;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.13, RF4 (CA4): the Business base moves to another embedding model while it is being asked. Thirty questions and the
 * request to move are released together; every question is answered (none lost, none failed), each answer names the same
 * documents the base named before the move, and the move itself is applied once. Since the model of the build embeds as the
 * old one under another name, an answer made of a part of the old index and a part of the new one would show as a
 * different list of documents. The refusal of the old model after the move belongs to the store (Stint 3b.5), which an
 * observer outside the worker cannot ask.
 */
class KnowledgeReindexIT extends PipelineScenario {

    static final int QUESTIONS = 30;

    @Test
    void asked_while_it_moves_the_base_answers_every_question_the_same_way() throws Exception {
        startPipeline(Map.of(Module.BUSINESS, Map.of("business.knowledge.operators", "ops")));
        KnowledgePlant plant = new KnowledgePlant(floci);
        plant.register("rec-rx-1", 1, "Members may borrow books. A late return blocks new loans.", "rx");
        plant.register("rec-rx-2", 1, "Every member has a limit of three books. Loans end with a return.", "rx");
        spy.until("both texts indexed", () -> spy.events().stream().filter(e -> e.type().equals(
                "io.sandbox.business.document-indexed.v1")).count() >= 2, Duration.ofMinutes(3));
        spy.settle(Duration.ofSeconds(3));

        plant.ask("business", "base-q", "late return blocks loans");
        List<String> baseline = answer("base-q");
        assertThat(baseline).isNotEmpty();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fired = new ArrayList<>();
        for (int i = 0; i < QUESTIONS; i++) {
            String id = "rx-q" + i;
            fired.add(pool.submit(() -> {
                go.await();
                plant.ask("business", id, "late return blocks loans");
                return null;
            }));
        }
        fired.add(pool.submit(() -> {
            go.await();
            plant.move("rx-move", "sandbox-embed-2");
            return null;
        }));
        go.countDown();
        for (Future<?> f : fired) {
            f.get();
        }
        pool.shutdown();

        spy.until("every question answered and the move reported", () -> {
            long answered = spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.question-answered.v1")
                    && subject(e).startsWith("rx-q")).count();
            return answered >= QUESTIONS && !moves().isEmpty();
        }, Duration.ofMinutes(4));
        spy.settle(Duration.ofSeconds(5));

        List<String> different = new ArrayList<>();
        for (int i = 0; i < QUESTIONS; i++) {
            List<String> documents = answer("rx-q" + i);
            if (!documents.equals(baseline)) {
                different.add("rx-q" + i + " " + documents);
            }
        }
        assertThat(different).as("answers that named other documents than the base did before the move").isEmpty();
        assertThat(moves()).as("the move is reported once").hasSize(1);
        assertThat(Spy.JSON.readTree(moves().getFirst().json()).path("data").path("status").asText()).isEqualTo("APPLIED");
        plant.move("rx-move-again", "sandbox-embed-2");
        spy.until("the same move again answered", () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.stintflow.knowledge.reindexed.v1") && "rx-move-again".equals(subject(e))), Duration.ofMinutes(2));
        assertThat(spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.reindexed.v1")
                && "rx-move-again".equals(subject(e))).map(e -> status(e)).toList()).containsExactly("UNCHANGED");
        Scenario.evidence("S.13-CA4.txt", "S.13 CA4: the Business base moves model while " + QUESTIONS + " questions arrive\n"
                + timeline() + "  baseline documents " + baseline + "; all " + QUESTIONS + " answers named the same; the move was"
                + " APPLIED once and then UNCHANGED\n");
    }

    private List<Spy.Event> moves() {
        return spy.events().stream().filter(e -> e.type().equals("io.stintflow.knowledge.reindexed.v1")
                && "rx-move".equals(subject(e))).toList();
    }

    private List<String> answer(String questionId) throws Exception {
        spy.until("answer " + questionId, () -> spy.events().stream().anyMatch(e -> e.type().equals(
                "io.sandbox.business.question-answered.v1") && questionId.equals(subject(e))), Duration.ofMinutes(2));
        Spy.Event found = spy.events().stream().filter(e -> e.type().equals("io.sandbox.business.question-answered.v1")
                && questionId.equals(subject(e))).findFirst().orElseThrow();
        JsonNode data = Spy.JSON.readTree(found.json()).path("data");
        List<String> out = new ArrayList<>();
        data.path("documents").forEach(d -> out.add(d.asText()));
        return out;
    }

    private static String status(Spy.Event e) {
        try {
            return Spy.JSON.readTree(e.json()).path("data").path("status").asText();
        } catch (Exception ex) {
            return "";
        }
    }
}
