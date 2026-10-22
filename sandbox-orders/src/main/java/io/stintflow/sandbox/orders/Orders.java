package io.stintflow.sandbox.orders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.3: the Orders domain's names. The event types and the source are those of {@code contracts/events.md}, the
 * only agreement with other domains; the routes are this domain's own.
 */
public final class Orders {

    public static final WorkflowRef PLACE_ORDER = new WorkflowRef("orders", "place-order", "1.0.0");

    public static final String ROUTE_SEND_CONFIRMATION = "send-confirmation";
    public static final String ROUTE_SEND_REJECTION = "send-rejection";
    public static final String ROUTE_SEND_DELAY_NOTICE = "send-delay-notice";

    public static final URI SOURCE = URI.create("https://sandbox.stintflow.io/orders");
    public static final String ORDER_PLACED = "io.sandbox.orders.order-placed.v1";
    public static final String ORDER_CONFIRMED = "io.sandbox.orders.order-confirmed.v1";
    public static final String INVOICE_REQUESTED = "io.sandbox.billing.invoice-requested.v1";
    public static final String INVOICE_ISSUED = "io.sandbox.billing.invoice-issued.v1";
    public static final String INVOICE_REJECTED = "io.sandbox.billing.invoice-rejected.v1";

    /** S.3 8b: the client's order id, which is also the idempotency key. */
    public static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private Orders() {
    }

    /** The chain of the whole order (contract, order-placed.v1): the same for every POST of the same order. */
    public static String chainId(String orderId) {
        return "chn-" + UUID.nameUUIDFromBytes(("order|" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    /** S.3 8a: the entry event of an order; its id is the order id, so a repeated POST is the same event. */
    public static CloudEvent placed(JsonNode order) {
        String orderId = order.path("orderId").asText();
        return CloudEventBuilder.v1()
                .withId(orderId)
                .withSource(SOURCE)
                .withType(ORDER_PLACED)
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withDataContentType("application/json")
                .withData(Json.bytes(order))
                .withExtension(StintEvents.EXT_CHAIN_ID, chainId(orderId))
                .build();
    }

    /** The instance of an order, known before it exists (Stint SDD 2.1: a function of the entry event's source and id). */
    public static String instanceId(String orderId) {
        return WorkflowEngine.triggeredInstanceId(PLACE_ORDER, CloudEventBuilder.v1()
                .withId(orderId).withSource(SOURCE).withType(ORDER_PLACED).build());
    }
}
