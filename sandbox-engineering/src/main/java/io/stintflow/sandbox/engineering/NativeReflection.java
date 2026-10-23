package io.stintflow.sandbox.engineering;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stintflow.sandbox.contracts.Corpus;

/**
 * SDD S.13, RF8 (finding S11-2, again): the corpus reader of {@code sandbox-contracts} builds its records with Jackson, by
 * reflection, and the contracts module may not depend on Quarkus (so it cannot carry this annotation itself). Without
 * it a native image of this service stops at start with "Cannot construct instance of Corpus$Meeting".
 */
@RegisterForReflection(targets = {Corpus.Speaker.class, Corpus.Turn.class, Corpus.Sdd.class, Corpus.Expected.class,
    Corpus.Pii.class, Corpus.Meeting.class})
public final class NativeReflection {

    private NativeReflection() {
    }
}
