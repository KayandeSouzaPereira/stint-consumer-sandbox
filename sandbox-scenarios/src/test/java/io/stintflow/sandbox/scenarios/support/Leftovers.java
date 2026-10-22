package io.stintflow.sandbox.scenarios.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * SDD S.4, 8e: what is left in floci once a scenario is over — the exact queries of the spec, run with the SDK and
 * printed with the equivalent {@code aws} command so they can be repeated by hand.
 */
public final class Leftovers {

    public static final List<String> DOMAINS = List.of("orders", "billing");
    /** SDD S.5: the meeting pipeline's domains with an engine. */
    public static final List<String> PIPELINE = List.of("business", "engineering", "delivery");
    static final List<String> WORK_QUEUES = List.of("tasks", "results", "timers", "domain-events");
    static final List<String> QUEUES = List.of("tasks", "results", "timers", "schedules", "domain-events");
    static final int OUTBOX_SHARDS = 4;

    /** One domain's counts. Queue depths are visible / in flight / delayed. */
    public record Domain(String domain, int waits, int outbox, int inbox, List<String> nonTerminal,
                         Map<String, int[]> workQueues, Map<String, int[]> dlqs, List<String> oneTimeSchedules) {

        public boolean workQueuesIdle() {
            return workQueues.values().stream().allMatch(d -> d[0] == 0 && d[1] == 0);
        }

        public int dlqMessages() {
            return dlqs.values().stream().mapToInt(d -> d[0] + d[1] + d[2]).sum();
        }
    }

    private final Floci floci;

    public Leftovers(Floci floci) {
        this.floci = floci;
    }

    public Domain of(String d) {
        int waits = floci.ddb.scan(r -> r.tableName(d + "-waits").select(Select.COUNT).consistentRead(true)).count();
        int outbox = 0;
        for (int shard = 0; shard < OUTBOX_SHARDS; shard++) {
            String s = Integer.toString(shard);
            outbox += floci.ddb.query(r -> r.tableName(d + "-outbox").indexName("pending-by-age")
                    .keyConditionExpression("shard = :s").expressionAttributeValues(Map.of(":s", AttributeValue.fromS(s)))
                    .select(Select.COUNT)).count();
        }
        int inbox = floci.ddb.scan(r -> r.tableName(d + "-inbox").select(Select.COUNT).consistentRead(true)).count();
        List<String> nonTerminal = new ArrayList<>();
        floci.ddb.scanPaginator(r -> r.tableName(d + "-instances").consistentRead(true)
                        .filterExpression("#s <> :c AND #s <> :f AND #s <> :a")
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(Map.of(":c", AttributeValue.fromS("COMPLETED"),
                                ":f", AttributeValue.fromS("FAILED"), ":a", AttributeValue.fromS("ABORTED"))))
                .items().forEach(i -> nonTerminal.add(i.get("instanceId").s() + "=" + i.get("status").s()));
        Map<String, int[]> work = new LinkedHashMap<>();
        for (String q : WORK_QUEUES) {
            work.put(d + "-" + q, depth(d + "-" + q));
        }
        Map<String, int[]> dlqs = new LinkedHashMap<>();
        for (String q : QUEUES) {
            dlqs.put(d + "-" + q + "-dlq", depth(d + "-" + q + "-dlq"));
        }
        List<String> schedules = floci.scheduler.listSchedules(r -> r.groupName(d + "-timers")).schedules().stream()
                .map(s -> s.name()).toList();
        return new Domain(d, waits, outbox, inbox, nonTerminal, work, dlqs, schedules);
    }

    public Map<String, Domain> all() {
        return all(DOMAINS);
    }

    public Map<String, Domain> all(List<String> domains) {
        Map<String, Domain> all = new LinkedHashMap<>();
        domains.forEach(d -> all.put(d, of(d)));
        return all;
    }

    /** Waits (long polls of the spy between checks) until no instance is running and no work queue holds a message. */
    public Map<String, Domain> awaitQuiet(Spy spy, Duration within) {
        return awaitQuiet(spy, within, DOMAINS);
    }

    public Map<String, Domain> awaitQuiet(Spy spy, Duration within, List<String> domains) {
        Map<String, Domain>[] last = new Map[1];
        spy.until("every instance terminal and every work queue idle", () -> {
            last[0] = all(domains);
            return last[0].values().stream().allMatch(d -> d.nonTerminal().isEmpty() && d.workQueuesIdle()
                    && d.outbox() == 0);
        }, within);
        return last[0];
    }

    public int[] depth(String queue) {
        Map<QueueAttributeName, String> a = floci.sqs.getQueueAttributes(r -> r.queueUrl(Floci.queueUrl(queue))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)).attributes();
        return new int[] {
                Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")),
                Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")),
                Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "0"))};
    }

    /** The evidence block: every count, with the aws command that gives it. */
    public static String report(Map<String, Domain> all) {
        StringBuilder out = new StringBuilder("Leftovers (SDD S.4, 8e), aws --endpoint-url http://localhost:4566 ...\n");
        for (Domain d : all.values()) {
            String n = d.domain();
            out.append(String.format("  [%s]%n", n));
            out.append(String.format("    waits               %3d   dynamodb scan --table-name %s-waits --select COUNT%n", d.waits(), n));
            out.append(String.format("    outbox (pending)    %3d   dynamodb query --table-name %s-outbox --index-name pending-by-age"
                    + " --key-condition-expression \"shard = :s\" (shards 0-3) --select COUNT%n", d.outbox(), n));
            out.append(String.format("    inbox               %3d   dynamodb scan --table-name %s-inbox --select COUNT%n", d.inbox(), n));
            out.append(String.format("    non-terminal        %3d   dynamodb scan --table-name %s-instances --filter-expression"
                    + " \"#s <> COMPLETED|FAILED|ABORTED\" %s%n", d.nonTerminal().size(), n, d.nonTerminal()));
            d.workQueues().forEach((q, v) -> out.append(String.format(
                    "    %-26s visible %d, in flight %d, delayed %d%n", q, v[0], v[1], v[2])));
            d.dlqs().forEach((q, v) -> out.append(String.format(
                    "    %-26s visible %d, in flight %d, delayed %d%n", q, v[0], v[1], v[2])));
            out.append(String.format("    one-time schedules  %3d   scheduler list-schedules --group-name %s-timers %s%n",
                    d.oneTimeSchedules().size(), n, d.oneTimeSchedules()));
        }
        out.append("  (sqs get-queue-attributes --attribute-names ApproximateNumberOfMessages"
                + " ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed)\n");
        out.append("  Delayed messages on <domain>-timers are timers still to fire (e.g. 5 min task timeouts). Once\n"
                + "  their instance is terminal, Stint acknowledges them as 'obsolete' (SDD 2.9); they are not leftovers.\n");
        return out.toString();
    }
}
