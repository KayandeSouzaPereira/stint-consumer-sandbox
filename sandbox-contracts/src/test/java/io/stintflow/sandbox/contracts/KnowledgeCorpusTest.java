package io.stintflow.sandbox.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** SDD S.14, RF1: the answer key of the knowledge is well formed and agrees with the meetings it comes from. */
class KnowledgeCorpusTest {

    static final Path ROOT = Path.of("..", "corpus");

    @Test
    void every_decision_is_held_by_the_words_of_its_meeting() throws Exception {
        KnowledgeCorpus kb = KnowledgeCorpus.load(ROOT);
        assertThat(kb.decisions()).isNotEmpty();
        for (KnowledgeCorpus.Decision d : kb.decisions()) {
            String script = Files.readString(ROOT.resolve("meetings/" + d.meetingId() + ".yaml")).toLowerCase();
            assertThat(script).as(d.key()).contains(d.surface().toLowerCase());
        }
        for (KnowledgeCorpus.Requirement r : kb.requirements()) {
            String script = Files.readString(ROOT.resolve("meetings/" + r.meetingId() + ".yaml")).toLowerCase();
            assertThat(script).as(r.key()).contains(r.surface().toLowerCase());
        }
    }

    @Test
    void every_draft_answers_keys_that_exist_and_has_a_verdict() {
        KnowledgeCorpus kb = KnowledgeCorpus.load(ROOT);
        var keys = new java.util.HashSet<String>();
        kb.decisions().forEach(d -> keys.add(d.key()));
        kb.requirements().forEach(r -> keys.add(r.key()));
        assertThat(kb.architecture()).isNotEmpty();
        kb.architecture().forEach(a -> a.drafts().forEach(d -> {
            assertThat(keys).as(d.id()).containsAll(d.motivatedBy());
            assertThat(d.verdict()).isIn("compatible", "conflicts");
        }));
        assertThat(kb.architecture().stream().flatMap(a -> a.drafts().stream()).map(KnowledgeCorpus.Draft::verdict))
                .contains("compatible", "conflicts");
    }

    @Test
    void no_marker_of_personal_data_is_in_the_key() throws Exception {
        assertThat(Files.readString(ROOT.resolve("kb.json"))).doesNotContain("SYNTH-PII");
    }
}
