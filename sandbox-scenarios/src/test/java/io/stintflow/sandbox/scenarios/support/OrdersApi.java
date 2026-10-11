package io.stintflow.sandbox.scenarios.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SDD S.4: Orders as a client sees it — {@code POST /orders} and {@code GET /orders/{orderId}} (S.3) — plus the
 * contract's names. Nothing here comes from a domain's code: the types are those of contracts/events.md, the chain
 * of an order is the one the contract states ({@code order-placed.v1}: {@code "chn-" + UUIDv3("order|" + orderId)}).
 */
public final class OrdersApi {

    public static final String ORDER_PLACED = "io.sandbox.orders.order-placed.v1";
    public static final String ORDER_CONFIRMED = "io.sandbox.orders.order-confirmed.v1";
    public static final String INVOICE_REQUESTED = "io.sandbox.billing.invoice-requested.v1";
    public static final String INVOICE_ISSUED = "io.sandbox.billing.invoice-issued.v1";
    public static final String INVOICE_REJECTED = "io.sandbox.billing.invoice-rejected.v1";

    private final HttpClient http = HttpClient.newHttpClient();

    public static String chainId(String orderId) {
        return "chn-" + UUID.nameUUIDFromBytes(("order|" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    public static String recipient(String orderId) {
        return orderId + "@example.com";
    }

    public static ObjectNode order(String orderId, double amount) {
        ObjectNode order = Spy.JSON.createObjectNode();
        order.put("orderId", orderId);
        order.put("customerId", "c-scenario");
        order.put("recipient", recipient(orderId));
        order.put("amount", amount);
        order.put("currency", "BRL");
        ObjectNode item = order.putArray("items").addObject();
        item.put("sku", "sku-1");
        item.put("quantity", 1);
        item.put("unitPrice", amount);
        return order;
    }

    /** POST /orders on the app at {@code port}: 202 with orderId and instanceId. */
    public JsonNode post(int port, ObjectNode order) {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(base(port)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(order.toString())).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("POST /orders on :" + port, e);
        }
        if (response.statusCode() != 202) {
            throw new AssertionError("POST /orders -> " + response.statusCode() + " " + response.body());
        }
        try {
            return Spy.JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(response.body(), e);
        }
    }

    /** GET /orders/{orderId}: the order's status (S.3 8d). */
    public JsonNode get(int port, String orderId) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base(port) + "/" + orderId))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            return Spy.JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("GET /orders/" + orderId + " on :" + port, e);
        }
    }

    public String status(int port, String orderId) {
        return get(port, orderId).path("status").asText();
    }

    private static URI base(int port) {
        return URI.create("http://localhost:" + port + "/orders");
    }
}
