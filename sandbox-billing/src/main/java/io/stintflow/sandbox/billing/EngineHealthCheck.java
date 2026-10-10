package io.stintflow.sandbox.billing;

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
    BillingEngine billing;

    @Override
    public HealthCheckResponse call() {
        boolean engineOk = !billing.engineEnabled() || billing.engineStarted();
        return HealthCheckResponse.named("billing-engine")
                .status(engineOk)
                .withData("engine", billing.engineEnabled() ? (billing.engineStarted() ? "started" : "starting") : "disabled")
                .withData("worker", billing.workerRunning() ? "running" : "disabled")
                .withData("connectors", "aws")
                .build();
    }
}
