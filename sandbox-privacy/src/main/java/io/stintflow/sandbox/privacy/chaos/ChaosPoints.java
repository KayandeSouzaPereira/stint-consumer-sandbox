package io.stintflow.sandbox.privacy.chaos;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.11, 8b (the pattern of S.4, 8b, once per sovereign domain): chaos points, off unless a property sets them.
 * {@code privacy.chaos.halt-in-task=<route>} halts the worker after the effect of that route's first task and before its
 * result is sent or the task acknowledged: the task comes back when its visibility expires.
 * A halt is {@code Runtime.halt(137)}: no shutdown hooks, nothing flushed, the closest a process gets to {@code kill -9}
 * from inside. Unset, the registry is is exactly the one it was given.
 */
public final class ChaosPoints {

    private static final Logger LOG = Logger.getLogger(ChaosPoints.class);

    /** The exit status of a halted process (128 + SIGKILL, as a shell reports a {@code kill -9}). */
    public static final int EXIT_STATUS = 137;

    /** How a chaos point ends the process; tests pass their own. */
    @FunctionalInterface
    public interface Halt {
        void halt(String point);
    }

    public static final Halt HALT = point -> {
        LOG.errorf("CHAOS halt at %s", point);
        System.out.flush();
        Runtime.getRuntime().halt(EXIT_STATUS);
    };

    private ChaosPoints() {
    }

    /** The worker's handlers, the one of {@code route} decorated only when a route is configured. */
    public static TaskHandlerRegistry registry(TaskHandlerRegistry registry, Optional<String> route, Halt halt) {
        if (route.isEmpty() || route.get().isBlank()) {
            return registry;
        }
        TaskHandler original = registry.find(route.get())
                .orElseThrow(() -> new IllegalArgumentException("No task handler for chaos route " + route.get()));
        LOG.warnf("CHAOS armed: the worker halts after the effect of its first %s task", route.get());
        return registry.register(route.get(), new Task(original, route.get(), halt));
    }

    private static final class Task implements TaskHandler {
        private final TaskHandler delegate;
        private final String route;
        private final Halt halt;
        private final AtomicBoolean fired = new AtomicBoolean();

        Task(TaskHandler delegate, String route, Halt halt) {
            this.delegate = delegate;
            this.route = route;
            this.halt = halt;
        }

        @Override
        public CompletionStage<JsonNode> execute(TaskContext ctx) {
            return delegate.execute(ctx).thenApply(result -> {
                if (fired.compareAndSet(false, true)) {
                    halt.halt("task " + route + " of instance " + ctx.instanceId() + " attempt " + ctx.attempt()
                            + ", after its effect, before its result");
                }
                return result;
            });
        }
    }
}
