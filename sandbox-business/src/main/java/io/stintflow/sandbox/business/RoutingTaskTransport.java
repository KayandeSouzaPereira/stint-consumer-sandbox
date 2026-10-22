package io.stintflow.sandbox.business;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.stintflow.aws.SqsTaskTransport;
import io.stintflow.spi.TaskInvocation;
import io.stintflow.wire.CeWire;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.5, 8c/8l: Stint's SQS transport, routed. A task invocation carries no reply address, so a service shared by
 * every domain (privacy, records, channels, target) has one queue per caller and answers on that caller's results
 * queue. This transport sends each routing key to its service's queue for this domain, and anything else to the
 * domain's own tasks queue; the results come back on the domain's results queue, read as by {@link SqsTaskTransport}.
 * It only implements Stint's public {@code TaskTransport} port: nothing in Stint changes. (Copied in each domain: no
 * domain depends on another.)
 */
public class RoutingTaskTransport extends SqsTaskTransport {

    private final SqsClient client;
    private final String ownQueue;
    private final Map<String, String> queueOfRoute;

    /**
     * @param queueBase    the queues' URL prefix
     * @param domain       this domain: {@code <domain>-tasks}, {@code <domain>-results}, {@code <service>-tasks-<domain>}
     * @param serviceOfRoute routing key -> shared service ({@code privacy}, {@code records}, {@code channels},
     *                     {@code target})
     */
    public RoutingTaskTransport(SqsClient sqs, String queueBase, String domain, Map<String, String> serviceOfRoute) {
        super(sqs, queueBase + domain + "-tasks", queueBase + domain + "-results");
        this.client = sqs;
        this.ownQueue = queueBase + domain + "-tasks";
        this.queueOfRoute = Map.copyOf(serviceOfRoute.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> queueBase + e.getValue() + "-tasks-" + domain)));
    }

    /** The queue a routing key goes to. */
    public String queueOf(String routingKey) {
        return queueOfRoute.getOrDefault(routingKey, ownQueue);
    }

    @Override
    public CompletionStage<Void> dispatch(TaskInvocation invocation) {
        return CompletableFuture.runAsync(() -> {
            String body = new String(CeWire.toJson(codec.toEvent(invocation)), StandardCharsets.UTF_8);
            client.sendMessage(r -> r.queueUrl(queueOf(invocation.routingKey())).messageBody(body));
        });
    }
}
