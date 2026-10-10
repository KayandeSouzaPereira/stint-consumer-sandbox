package io.stintflow.sandbox.billing.worker;

import java.time.Duration;

import io.stintflow.sandbox.billing.Billing;
import io.stintflow.worker.TaskFailure;

/**
 * SDD S.2, RF4: faults injected <em>only</em> here, in the renderer, keyed on the request's {@code orderId} (see the
 * Billing README):
 * <ul>
 *   <li>{@value #FAIL_503_PREFIX}: attempts 1 and 2 fail with a DSL 1.0 {@code communication} error and status
 *       503 (transient: the workflow retries it); attempt 3 succeeds;</li>
 *   <li>{@value #SLOW_PREFIX}: the render takes {@link #slowDelay()} longer.</li>
 * </ul>
 * Nothing is stored: the attempt number comes from the task invocation, so the behavior is deterministic.
 */
public record FaultInjection(Duration slowDelay) {

    public static final String FAIL_503_PREFIX = "fail503-";
    public static final String SLOW_PREFIX = "slow-";
    static final int FAILING_ATTEMPTS = 2;

    /** Throws the injected failure for this attempt, if any. */
    void beforeRender(String orderId, int attempt) {
        if (orderId.startsWith(FAIL_503_PREFIX) && attempt <= FAILING_ATTEMPTS) {
            throw new TaskFailure(Billing.COMMUNICATION_ERROR, 503,
                    "Injected fault: renderer unavailable (attempt " + attempt + ")", null);
        }
    }

    /** The extra delay for this order. */
    Duration delayFor(String orderId) {
        return orderId.startsWith(SLOW_PREFIX) ? slowDelay : Duration.ZERO;
    }
}
