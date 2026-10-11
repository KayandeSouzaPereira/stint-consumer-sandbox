package io.stintflow.sandbox.billing.chaos;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.4, RF2a: halts the worker on the first task of one route, after the handler did its work (its external
 * effect happened) and before the result is sent or the task acknowledged — the worst moment for a worker. The task
 * comes back to another worker when its visibility expires. Mounted only when {@code billing.chaos.halt-in-task} is
 * set.
 */
public final class ChaosTaskHandler implements TaskHandler {

    private final TaskHandler delegate;
    private final String route;
    private final Chaos chaos;
    private final AtomicBoolean fired = new AtomicBoolean();

    public ChaosTaskHandler(TaskHandler delegate, String route, Chaos chaos) {
        this.delegate = delegate;
        this.route = route;
        this.chaos = chaos;
    }

    /** Replaces the handler of {@code route} in {@code registry} with one that halts after its first task. */
    public static TaskHandlerRegistry wrap(TaskHandlerRegistry registry, String route, Chaos chaos) {
        TaskHandler original = registry.find(route)
                .orElseThrow(() -> new IllegalArgumentException("No task handler for chaos route " + route));
        return registry.register(route, new ChaosTaskHandler(original, route, chaos));
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        return delegate.execute(ctx).thenApply(result -> {
            if (fired.compareAndSet(false, true)) {
                chaos.halt("task " + route + " of instance " + ctx.instanceId() + " attempt " + ctx.attempt()
                        + ", after its effect, before its result");
            }
            return result;
        });
    }
}
