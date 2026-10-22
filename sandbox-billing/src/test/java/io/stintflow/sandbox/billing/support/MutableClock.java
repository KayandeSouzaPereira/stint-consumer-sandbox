package io.stintflow.sandbox.billing.support;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;

/** A controllable clock: time moves only when a test says so. */
public final class MutableClock implements InstantSource {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        now = new AtomicReference<>(start);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void set(Instant instant) {
        now.set(instant);
    }
}
