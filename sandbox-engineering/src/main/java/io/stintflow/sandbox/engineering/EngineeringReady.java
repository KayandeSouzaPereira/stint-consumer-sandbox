package io.stintflow.sandbox.engineering;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Ready once the engine and the worker this process runs have started (the scenarios wait on it). */
@Readiness
@ApplicationScoped
public class EngineeringReady implements HealthCheck {

    @Inject
    EngineeringEngine engine;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("engineering").status(engine.ready()).build();
    }
}
