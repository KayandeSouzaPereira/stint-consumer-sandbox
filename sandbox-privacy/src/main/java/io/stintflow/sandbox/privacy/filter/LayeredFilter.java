package io.stintflow.sandbox.privacy.filter;

import java.util.ArrayList;
import java.util.List;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.RedactionLayer;
import io.stintflow.worker.redaction.TermListLayer;

/**
 * SDD S.6, 8a: the pipeline's PII filter — Stint's {@link LayeredRedactor} over, in order: the spoken forms, the
 * reference patterns, the listed and the introduced names, and (if given) the local model's check. Each layer works on what
 * the previous ones left, so the model, the most costly and least sure, sees only the remainder. The spoken forms come
 * first: the reference patterns are in doubt about a run of digits they cannot classify ("5511 98022 4022"), and a
 * dictated number is better replaced before they look (the cost: an IP or a card written in digits is typed as a
 * number, not as itself; it is redacted all the same).
 */
public final class LayeredFilter {

    public static final String NAME = "sandbox-layered";
    public static final String VERSION = "s6";

    private LayeredFilter() {
    }

    /**
     * @param listedNames the synthetic list of names, as written ({@code SYNTH-PII-2011 Rivera}), also the markers alone
     * @param check       the local model's check, or null for none (a test of the other layers)
     */
    public static LayeredRedactor build(List<String> listedNames, PersonalDataCheck check, double threshold) {
        List<RedactionLayer> layers = new ArrayList<>(List.of(new SpokenFormLayer(), new PatternLayer(),
                new NameLayer(listedNames)));
        List<String> surnames = surnamesOf(listedNames);
        if (!surnames.isEmpty()) {
            layers.add(new TermListLayer("person", surnames));   // before the introductions: they absorb what precedes it
        }
        layers.add(new PresentationLayer());
        if (check != null) {
            layers.add(new LlmCheckLayer(check, threshold));
        }
        return new LayeredRedactor(NAME, VERSION, layers);
    }

    /** The word after the marker of each listed name ({@code SYNTH-PII-2011 Rivera} -> {@code Rivera}), if there is one. */
    static List<String> surnamesOf(List<String> listedNames) {
        return listedNames.stream().map(n -> n.trim().split("\\s+")).filter(parts -> parts.length == 2)
                .map(parts -> parts[1]).distinct().toList();
    }

    /** The names of a corpus: each name's written form and each marker on its own (any person-bearing marker). */
    public static List<String> namesOf(Corpus corpus) {
        List<String> names = new ArrayList<>();
        for (Corpus.Meeting meeting : corpus.meetings()) {
            for (Corpus.Pii pii : meeting.pii()) {
                if ("name".equals(pii.kind())) {
                    names.add(pii.written());
                }
                names.add(pii.marker());
            }
        }
        return names.stream().distinct().toList();
    }
}
