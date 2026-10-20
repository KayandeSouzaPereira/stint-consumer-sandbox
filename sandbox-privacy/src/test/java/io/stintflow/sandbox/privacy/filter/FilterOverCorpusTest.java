package io.stintflow.sandbox.privacy.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionLayer;
import io.stintflow.worker.redaction.RedactionLayer.Scope;

/**
 * SDD S.6, CA1/CA3/CA7: the whole filter over every meeting of the corpus, in the written form and in each spoken form
 * of every planted marker — nothing escapes, the model's doubt is the redaction's doubt, a model that fails is doubt
 * too, and the redacted text keeps its meaning (90 % of the words that are not personal data survive).
 */
class FilterOverCorpusTest {

    static final Corpus CORPUS = Corpus.load(Path.of(System.getProperty("sandbox.root", "..")).resolve("corpus"));
    static final Scope SCOPE = new Scope("i-1", Set.of(),
            new Pseudonyms("filter-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
    static final Pattern WORD = Pattern.compile("[\\p{L}]{3,}");

    /** The meeting's script with every planted datum replaced by its n-th spoken form (n < 0: the written form). */
    static String transcript(Corpus.Meeting m, int form) {
        String text = m.text();
        for (Corpus.Pii pii : m.pii()) {
            if (form >= 0) {
                String spoken = pii.spoken().get(Math.min(form, pii.spoken().size() - 1));
                text = text.replace(pii.kind().equals("phone") ? pii.written() + " (" + pii.marker() + ")" : pii.written(),
                        spoken);
            }
        }
        return text;
    }

    @Test
    void no_marker_survives_in_any_form_for_any_meeting() {
        LayeredRedactor filter = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), new CorpusKeyCheck(CORPUS), 0.5);
        List<String> escaped = new ArrayList<>();
        int checked = 0;
        for (Corpus.Meeting m : CORPUS.meetings()) {
            for (int form = -1; form < 2; form++) {
                String text = transcript(m, form);
                RedactionLayer.Result result = filter.redact(text, SCOPE);
                checked++;
                CORPUS.leaksIn(result.text()).forEach(l -> escaped.add(m.id() + " form " + l));
                assertThat(result.doubt()).as(m.id() + " form " + text.length()).isFalse();
            }
        }
        assertThat(checked).isEqualTo(CORPUS.meetings().size() * 3);
        assertThat(escaped).as("markers that escaped the filter").isEmpty();
    }

    @Test
    void the_redacted_text_keeps_its_meaning() {
        LayeredRedactor filter = LayeredFilter.build(LayeredFilter.namesOf(CORPUS), new CorpusKeyCheck(CORPUS), 0.5);
        for (Corpus.Meeting m : CORPUS.meetings()) {
            String before = m.text();
            String after = filter.redact(transcript(m, 0), SCOPE).text();
            long kept = words(before).stream().filter(w -> !isPersonal(m, w)).filter(w -> words(after).contains(w))
                    .count();
            long total = words(before).stream().filter(w -> !isPersonal(m, w)).count();
            assertThat((double) kept / total).as(m.id()).isGreaterThanOrEqualTo(0.9);
        }
    }

    @Test
    void a_model_in_doubt_or_failing_puts_the_redaction_in_doubt_and_never_clears_it() {
        String clean = "The design is for readers who search the catalog by author.";
        assertThat(build(t -> 0.9).redact(clean, SCOPE).doubt()).as("noul above the threshold").isTrue();
        assertThat(build(t -> 0.5).redact(clean, SCOPE).doubt()).as("noul at the threshold").isTrue();
        assertThat(build(t -> 0.49).redact(clean, SCOPE).doubt()).as("noul below it").isFalse();
        assertThat(build(t -> {
            throw new java.io.IOException("LitJev is down");
        }).redact(clean, SCOPE).doubt()).as("a model that fails").isTrue();
        assertThat(build(t -> Double.NaN).redact(clean, SCOPE).doubt()).as("an answer that is not a probability").isTrue();
        assertThat(build(t -> 2.0).redact(clean, SCOPE).doubt()).as("an answer out of range").isTrue();
        // the model sees the text as the earlier layers left it, and its answer never changes the text
        List<String> seen = new ArrayList<>();
        RedactionLayer.Result result = build(t -> {
            seen.add(t);
            return 0.1;
        }).redact("Mail synth p i i two zero one two at example dot com today.", SCOPE);
        assertThat(seen).singleElement().asString().doesNotContain("example").contains("SPOKEN-EMAIL");
        assertThat(result.text()).isEqualTo(seen.get(0));
    }

    @Test
    void a_layer_in_doubt_is_the_redactions_doubt() {
        RedactionLayer unsure = new RedactionLayer() {
            @Override
            public String name() {
                return "unsure";
            }

            @Override
            public Result apply(String text, Scope scope) {
                return new Result(text, java.util.Map.of(), true);
            }
        };
        LayeredRedactor filter = new LayeredRedactor("t", "1", List.of(new SpokenFormLayer(), unsure));
        assertThat(filter.redact("Hello there.", SCOPE).doubt()).isTrue();
    }

    static LayeredRedactor build(PersonalDataCheck check) {
        return LayeredFilter.build(LayeredFilter.namesOf(CORPUS), check, 0.5);
    }

    static Set<String> words(String text) {
        Set<String> out = new java.util.HashSet<>();
        var m = WORD.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** A word that is part of a planted datum (its marker, name, e-mail or phone), so not expected to survive. */
    static boolean isPersonal(Corpus.Meeting m, String word) {
        return m.pii().stream().anyMatch(p -> p.written().toLowerCase(java.util.Locale.ROOT).contains(word)
                || p.marker().toLowerCase(java.util.Locale.ROOT).contains(word));
    }
}
