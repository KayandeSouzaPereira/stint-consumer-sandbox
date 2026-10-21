package io.stintflow.sandbox.delivery;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Ready once the engine and the worker this process runs have started (the scenarios wait on it). */
@Readiness
@ApplicationScoped
public class DeliveryReady implements HealthCheck {

    @Inject
    DeliveryEngine engine;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("delivery").status(engine.ready()).build();
    }
}
