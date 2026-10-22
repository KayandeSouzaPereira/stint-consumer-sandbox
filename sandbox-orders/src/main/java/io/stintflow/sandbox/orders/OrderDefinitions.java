package io.stintflow.sandbox.orders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.stintflow.core.WorkflowDefinition;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;

/**
 * SDD S.3, 8f: loads {@code place-order} with its two {@code listen} timeouts taken from configuration. The YAML holds
 * the defaults; this replaces {@code timeout.after} of the {@code await} and {@code awaitLate} listens in the parsed
 * tree and hands the tree to Stint's own loader (strict), so the definition is still validated by Stint.
 */
public final class OrderDefinitions {

    public static final String RESOURCE = "stint/workflows/place-order.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private OrderDefinitions() {
    }

    public static WorkflowDefinition placeOrder(Duration invoiceTimeout, Duration lateInvoiceTimeout) {
        for (Duration timeout : List.of(invoiceTimeout, lateInvoiceTimeout)) {
            // S.4 8a: Stint 2.9 redelivers a timer that fires before its wait is saved, so any positive timeout works.
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("A listen timeout must be positive, not " + timeout);
            }
        }
        JsonNode tree;
        try (InputStream in = OrderDefinitions.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            tree = YAML.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        setTimeout(tree, "await", "reply", invoiceTimeout);
        setTimeout(tree, "awaitLate", "lateReply", lateInvoiceTimeout);
        try {
            return new DslLoader().load(new ByteArrayInputStream(YAML.writeValueAsBytes(tree)), RESOURCE,
                    LoadOptions.STRICT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setTimeout(JsonNode tree, String tryTask, String listenTask, Duration after) {
        for (JsonNode task : tree.path("do")) {
            JsonNode body = task.get(tryTask);
            if (body != null) {
                for (JsonNode inner : body.path("try")) {
                    JsonNode listen = inner.get(listenTask);
                    if (listen != null && listen.has("listen")) {
                        ((ObjectNode) listen.path("timeout")).put("after", after.toString());
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException(RESOURCE + " has no listen '" + listenTask + "' in try '" + tryTask + "'");
    }
}
