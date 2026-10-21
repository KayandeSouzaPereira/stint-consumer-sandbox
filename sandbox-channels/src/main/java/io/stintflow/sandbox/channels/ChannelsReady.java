package io.stintflow.sandbox.channels;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The scenarios wait on /q/health/ready before driving the channels. */
@Readiness
@ApplicationScoped
public class ChannelsReady implements HealthCheck {

    @Inject
    ChannelsService service;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("channels").status(service.ready()).build();
    }
}
