package io.stintflow.sandbox.billing.worker;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.spi.ErrorInfo;
import io.stintflow.worker.TaskContext;
import io.stintflow.worker.TaskFailure;
import io.stintflow.worker.TaskHandler;
import io.stintflow.worker.TaskHandlerRegistry;

/**
 * SDD S.2 (CA3) and S.5 (RF9): logs a failed task with its route, attempt and instance and the DSL 1.0 error type and
 * status, at the handler. With the worker on Stint's {@code SqsWorkerBinding} the result no longer passes through the
 * app, so the line the S.2 test reads is written here, by a decorator over every route. (Copied in each domain.)
 */
public final class FailureLog implements TaskHandler {

    /** Under the engine's logger name, where the failure line has always been written (the S.2 test listens there). */
    private static final Logger LOG = Logger.getLogger("io.stintflow.sandbox.billing.BillingEngine");

    private final String route;
    private final TaskHandler delegate;

    private FailureLog(String route, TaskHandler delegate) {
        this.route = route;
        this.delegate = delegate;
    }

    /** Decorates the handler of each of {@code routes} in {@code registry}. */
    public static TaskHandlerRegistry wrap(TaskHandlerRegistry registry, List<String> routes) {
        for (String route : routes) {
            registry.find(route).ifPresent(handler -> registry.register(route, new FailureLog(route, handler)));
        }
        return registry;
    }

    @Override
    public CompletionStage<JsonNode> execute(TaskContext ctx) {
        try {
            return delegate.execute(ctx).whenComplete((result, failure) -> {
                if (failure != null) {
                    log(ctx, failure);
                }
            });
        } catch (RuntimeException e) {
            log(ctx, e);
            throw e;
        }
    }

    private void log(TaskContext ctx, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause()
                : failure;
        if (cause instanceof TaskFailure task) {
            ErrorInfo error = task.toErrorInfo();
            LOG.warnf("Task %s attempt %s of instance %s failed: type=%s status=%s detail=%s", route, ctx.attempt(),
                    ctx.instanceId(), error.type(), error.status(), error.detail());
        } else {
            LOG.warnf("Task %s attempt %s of instance %s failed: %s", route, ctx.attempt(), ctx.instanceId(),
                    cause.getClass().getSimpleName());
        }
    }
}
