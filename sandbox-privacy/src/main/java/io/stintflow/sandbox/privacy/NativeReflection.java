package io.stintflow.sandbox.privacy;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stintflow.sandbox.contracts.Corpus;

/**
 * SDD S.11, RF6 (finding S11-2): the corpus reader of {@code sandbox-contracts} builds its records with Jackson, by
 * reflection, and the contracts module may not depend on Quarkus (so it cannot carry this annotation itself). Without
 * it a native image of this service stops at start with "Cannot construct instance of Corpus$Meeting".
 */
@RegisterForReflection(targets = {Corpus.Speaker.class, Corpus.Turn.class, Corpus.Sdd.class, Corpus.Expected.class,
    Corpus.Pii.class, Corpus.Meeting.class})
public final class NativeReflection {

    private NativeReflection() {
    }
}
