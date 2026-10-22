package io.stintflow.sandbox.billing.chaos;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.cloudevents.CloudEvent;
import io.stintflow.spi.AdapterCapabilities;
import io.stintflow.spi.EventPublisher;

/**
 * SDD S.4, RF2b: a decorator of Stint's public {@link EventPublisher} port that halts the process on the first
 * publication of one event type, before handing it to the broker. Stint calls the publisher only from its outbox
 * relay, after the checkpoint that recorded the fact (SDD 2.2), so the halt falls exactly between the checkpoint and
 * the publication: the fact is left in the outbox only. Mounted only when {@code billing.chaos.halt-before-publish}
 * is set; otherwise the engine gets the publisher itself.
 */
public final class ChaosEventPublisher implements EventPublisher {

    private final EventPublisher delegate;
    private final String type;
    private final Chaos chaos;
    private final AtomicBoolean fired = new AtomicBoolean();

    public ChaosEventPublisher(EventPublisher delegate, String type, Chaos chaos) {
        this.delegate = delegate;
        this.type = type;
        this.chaos = chaos;
    }

    @Override
    public CompletionStage<Void> publish(CloudEvent event) {
        if (type.equals(event.getType()) && fired.compareAndSet(false, true)) {
            chaos.halt("publish of " + event.getType() + " id=" + event.getId() + " subject=" + event.getSubject());
        }
        return delegate.publish(event);
    }

    @Override
    public AdapterCapabilities capabilities() {
        return delegate.capabilities();
    }
}
