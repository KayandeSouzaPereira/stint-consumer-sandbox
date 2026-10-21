package io.stintflow.sandbox.business;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Ready once the engine and the worker this process runs have started (the scenarios wait on it). */
@Readiness
@ApplicationScoped
public class BusinessReady implements HealthCheck {

    @Inject
    BusinessEngine engine;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("business").status(engine.ready()).build();
    }
}
