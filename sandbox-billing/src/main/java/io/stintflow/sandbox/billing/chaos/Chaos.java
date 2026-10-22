package io.stintflow.sandbox.billing.chaos;

import org.jboss.logging.Logger;

/**
 * SDD S.4, 8b: how a chaos point ends the process. {@link #HALT} is {@code Runtime.halt(137)}: no shutdown hooks, no
 * {@code ShutdownEvent}, nothing flushed or acknowledged — the closest a process gets to {@code kill -9} from inside.
 * Every chaos point is off unless its {@code billing.chaos.*} property is set; tests pass their own {@link Chaos}.
 */
@FunctionalInterface
public interface Chaos {

    /** The exit status of a halted process (128 + SIGKILL, as a shell reports a {@code kill -9}). */
    int EXIT_STATUS = 137;

    Chaos HALT = point -> {
        Logger.getLogger(Chaos.class).errorf("CHAOS halt at %s", point);
        System.out.flush();
        Runtime.getRuntime().halt(EXIT_STATUS);
    };

    /** Ends the process at {@code point} (a description for the log line the scenario waits for). */
    void halt(String point);
}
