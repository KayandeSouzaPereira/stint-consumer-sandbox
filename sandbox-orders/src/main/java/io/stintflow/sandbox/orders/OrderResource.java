package io.stintflow.sandbox.orders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.InstanceSnapshot.InstanceStatus;
import io.stintflow.wire.CeWire;
import io.stintflow.wire.Json;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * SDD S.3, RF1: the order's HTTP entry.
 * <ul>
 *   <li>{@code POST /orders} checks the shape, notes the order as received, and publishes {@code order-placed.v1}
 *       with the client's {@code orderId} as its id (8a/8b). {@code place-order} starts from it by
 *       {@code schedule.on}, so a repeated POST is the same event and the same instance (Stint SDD 2.1). Answers 202
 *       with the {@code instanceId}, known before the instance exists.</li>
 *   <li>{@code GET /orders/{orderId}} reads the order's status from its Stint snapshot (8d).</li>
 * </ul>
 * The "received" note ({@code received#<orderId>} in {@code orders-effects}) only tells a received order whose
 * instance doesn't exist yet from an unknown one; the snapshot is the order's state.
 */
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrdersEngine orders;
    @Inject
    EventBridgeClient eventBridge;
    @Inject
    DynamoDbClient ddb;

    @ConfigProperty(name = "orders.eventbridge.domain-bus")
    String domainBus;
    @ConfigProperty(name = "orders.dynamodb.effects-table")
    String effectsTable;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response place(JsonNode body) {
        List<String> errors = shapeErrors(body);
        if (!errors.isEmpty()) {
            ObjectNode out = Json.obj();
            errors.forEach(out.putArray("errors")::add);
            return Response.status(Response.Status.BAD_REQUEST).entity(out).build();
        }
        ObjectNode order = Json.obj();
        for (String field : List.of("orderId", "customerId", "recipient", "amount", "currency", "items")) {
            if (body.hasNonNull(field)) {
                order.set(field, body.get(field));
            }
        }
        String orderId = order.get("orderId").asText();
        noteReceived(orderId);
        CloudEvent placed = Orders.placed(order);
        PutEventsResponse response = eventBridge.putEvents(r -> r.entries(e -> e.eventBusName(domainBus)
                .source(placed.getSource().toString())
                .detailType(placed.getType())
                .detail(new String(CeWire.toJson(placed), StandardCharsets.UTF_8))));
        if (response.failedEntryCount() > 0) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Json.obj().put("error", "the order could not be published; retry the same request")).build();
        }
        ObjectNode out = Json.obj();
        out.put("orderId", orderId);
        out.put("instanceId", Orders.instanceId(orderId));
        out.put("status", "received");
        return Response.accepted(out).build();
    }

    @GET
    @Path("/{orderId}")
    public Response status(@PathParam("orderId") String orderId) throws Exception {
        if (!Orders.ORDER_ID.matcher(orderId).matches()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String instanceId = Orders.instanceId(orderId);
        Optional<InstanceSnapshot> snapshot = orders.state().load(instanceId).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        ObjectNode out = Json.obj();
        out.put("orderId", orderId);
        out.put("instanceId", instanceId);
        if (snapshot.isEmpty()) {
            if (!wasReceived(orderId)) {
                return Response.status(Response.Status.NOT_FOUND).entity(out.put("status", "unknown")).build();
            }
            return Response.ok(out.put("status", "received")).build();
        }
        InstanceSnapshot snap = snapshot.get();
        JsonNode context = snap.context();
        out.put("status", snap.status() == InstanceStatus.FAILED ? "failed" : context.path("status").asText("requested"));
        if (context.hasNonNull("invoiceId")) {
            out.put("invoiceId", context.get("invoiceId").asText());
        }
        if (context.hasNonNull("reason")) {
            out.put("reason", context.get("reason").asText());
        }
        return Response.ok(out).build();
    }

    /** The order's shape only (RF2): whether it can be invoiced is not Orders' call. */
    static List<String> shapeErrors(JsonNode body) {
        List<String> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            return List.of("the body must be a JSON object");
        }
        if (!body.path("orderId").isTextual() || !Orders.ORDER_ID.matcher(body.path("orderId").asText()).matches()) {
            errors.add("orderId must match " + Orders.ORDER_ID.pattern());
        }
        for (String field : List.of("customerId", "recipient", "currency")) {
            if (!body.path(field).isTextual() || body.path(field).asText().isBlank()) {
                errors.add(field + " is required");
            }
        }
        if (!body.path("amount").isNumber()) {
            errors.add("amount must be a number");
        }
        if (body.hasNonNull("items") && !body.get("items").isArray()) {
            errors.add("items must be an array");
        }
        return errors;
    }

    private void noteReceived(String orderId) {
        try {
            ddb.putItem(r -> r.tableName(effectsTable)
                    .item(Map.of("effectKey", AttributeValue.fromS("received#" + orderId),
                            "markedAt", AttributeValue.fromS(Instant.now().toString())))
                    .conditionExpression("attribute_not_exists(effectKey)"));
        } catch (ConditionalCheckFailedException alreadyReceived) {
            // a repeated POST of the same order: nothing to note
        }
    }

    private boolean wasReceived(String orderId) {
        return ddb.getItem(r -> r.tableName(effectsTable).consistentRead(true)
                .key(Map.of("effectKey", AttributeValue.fromS("received#" + orderId)))).hasItem();
    }
}
