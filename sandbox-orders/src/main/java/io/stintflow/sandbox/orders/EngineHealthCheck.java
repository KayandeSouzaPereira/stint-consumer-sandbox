package io.stintflow.sandbox.orders;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** SDD S.1, CA3: ready once the roles this process runs are up (engine over the AWS connectors, worker loop). */
@Readiness
@ApplicationScoped
public class EngineHealthCheck implements HealthCheck {

    @Inject
    OrdersEngine orders;

    @Override
    public HealthCheckResponse call() {
        boolean engineOk = !orders.engineEnabled() || orders.engineStarted();
        return HealthCheckResponse.named("orders-engine")
                .status(engineOk)
                .withData("engine", orders.engineEnabled() ? (orders.engineStarted() ? "started" : "starting") : "disabled")
                .withData("worker", orders.workerRunning() ? "running" : "disabled")
                .withData("connectors", "aws")
                .build();
    }
}
