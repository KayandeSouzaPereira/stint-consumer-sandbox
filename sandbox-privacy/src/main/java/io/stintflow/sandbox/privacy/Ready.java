package io.stintflow.sandbox.privacy;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Ready once every caller's worker binding runs (the scenarios wait on /q/health/ready). */
@Readiness
@ApplicationScoped
public class Ready implements HealthCheck {

    @Inject
    PrivacyWorker worker;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("privacy-worker").status(worker.started()).build();
    }
}
