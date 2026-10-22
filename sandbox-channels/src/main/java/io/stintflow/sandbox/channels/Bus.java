package io.stintflow.sandbox.channels;

import java.nio.charset.StandardCharsets;

import io.cloudevents.CloudEvent;
import io.stintflow.wire.CeWire;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * SDD S.5: the channels' events onto the {@code sandbox-domain} bus — the CloudEvent in structured JSON as the detail,
 * its type as the detail-type (as Stint's EventBridge publisher, {@code contracts/events.md}).
 */
public final class Bus {

    private final EventBridgeClient eventBridge;
    private final String busName;

    public Bus(EventBridgeClient eventBridge, String busName) {
        this.eventBridge = eventBridge;
        this.busName = busName;
    }

    public void publish(CloudEvent event) {
        String detail = new String(CeWire.toJson(event), StandardCharsets.UTF_8);
        PutEventsResponse response = eventBridge.putEvents(r -> r.entries(e -> e.eventBusName(busName)
                .source(event.getSource().toString()).detailType(event.getType()).detail(detail)));
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            throw new IllegalStateException("the bus refused " + event.getType() + " " + event.getId() + ": "
                    + response.entries().get(0).errorCode());
        }
    }
}
