package io.stintflow.sandbox.privacy.filter;

import io.stintflow.sandbox.contracts.Corpus;

/**
 * SDD S.6, 8a (the CI): a stand-in for the local model that knows the corpus's planted markers. It says "yes, personal
 * data is left" (0.95) when a marker, in any of its forms, survived the earlier layers, and "no" (0.05) otherwise — so
 * an escaped marker puts the redaction in doubt even if every rule failed. It stands for a good model, not for LitJev
 * (whose real answers are measured in the manual run): the deterministic layers are what the tests must prove.
 */
public final class CorpusKeyCheck implements PersonalDataCheck {

    private final Corpus corpus;

    public CorpusKeyCheck(Corpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public double likelihood(String text) {
        return corpus.leaksIn(text).isEmpty() ? 0.05 : 0.95;
    }
}
