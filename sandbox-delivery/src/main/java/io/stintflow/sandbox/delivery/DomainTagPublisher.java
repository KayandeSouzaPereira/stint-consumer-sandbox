package io.stintflow.sandbox.delivery;

import java.util.concurrent.CompletionStage;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.AdapterCapabilities;
import io.stintflow.spi.EventPublisher;

/**
 * SDD S.5, 8b/8g: the domain's publisher, decorated. Stint's review requests leave the engine with
 * {@code source: stint://engine/review} and nothing that says which domain asked; this decorator adds
 * {@code sandboxdomain} to the {@code io.stintflow.review.*} events, so the portal routes the answer back. Every other
 * event passes unchanged. It decorates Stint's public {@code EventPublisher} port, as S.4's chaos publisher did.
 * (Copied in each domain.)
 */
public final class DomainTagPublisher implements EventPublisher {

    private final EventPublisher delegate;
    private final String domain;

    public DomainTagPublisher(EventPublisher delegate, String domain) {
        this.delegate = delegate;
        this.domain = domain;
    }

    @Override
    public CompletionStage<Void> publish(CloudEvent event) {
        if (event.getType().startsWith(PipelineEvents.REVIEW_PREFIX)) {
            return delegate.publish(CloudEventBuilder.v1(event).withExtension(PipelineEvents.EXT_DOMAIN, domain)
                    .build());
        }
        return delegate.publish(event);
    }

    @Override
    public AdapterCapabilities capabilities() {
        return delegate.capabilities();
    }
}
