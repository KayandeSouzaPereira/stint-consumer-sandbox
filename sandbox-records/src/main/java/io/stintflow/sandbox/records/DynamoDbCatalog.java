package io.stintflow.sandbox.records;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** SDD S.5, 8j: the catalog in {@code records-catalog} (key {@code recordId} + {@code version}). */
public final class DynamoDbCatalog implements RegisterDocumentHandler.Catalog {

    private final DynamoDbClient ddb;
    private final String table;

    public DynamoDbCatalog(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    @Override
    public Optional<Entry> latest(String recordId) {
        var items = ddb.query(q -> q.tableName(table).keyConditionExpression("recordId = :r")
                .expressionAttributeValues(Map.of(":r", AttributeValue.fromS(recordId))).scanIndexForward(false)
                .limit(1).consistentRead(true)).items();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entry(items.get(0)));
    }

    @Override
    public Optional<Entry> get(String recordId, int version) {
        var item = ddb.getItem(g -> g.tableName(table).consistentRead(true).key(Map.of("recordId",
                AttributeValue.fromS(recordId), "version", AttributeValue.fromN(String.valueOf(version))))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(entry(item));
    }

    private static Entry entry(Map<String, AttributeValue> i) {
        return new Entry(s(i, "recordId"), Integer.parseInt(i.get("version").n()), s(i, "sha256"), s(i, "kind"),
                s(i, "ownerRole"), s(i, "sensitivity"), s(i, "storedRef"), s(i, "projectId"), s(i, "cycle"),
                s(i, "sourceMeetingId"), s(i, "chainId"), s(i, "causationId"),
                i.get("supersedes") == null ? null : Integer.valueOf(i.get("supersedes").n()),
                s(i, "retentionClass"));
    }

    @Override
    public boolean putIfAbsent(Entry e) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("recordId", AttributeValue.fromS(e.recordId()));
        item.put("version", AttributeValue.fromN(String.valueOf(e.version())));
        item.put("sha256", AttributeValue.fromS(e.sha256()));
        item.put("kind", AttributeValue.fromS(e.kind()));
        item.put("ownerRole", AttributeValue.fromS(e.ownerRole()));
        item.put("sensitivity", AttributeValue.fromS(e.sensitivity()));
        item.put("storedRef", AttributeValue.fromS(e.storedRef()));
        if (e.projectId() != null) {
            item.put("projectId", AttributeValue.fromS(e.projectId()));
        }
        if (e.cycle() != null) {
            item.put("cycle", AttributeValue.fromS(e.cycle()));
        }
        put(item, "sourceMeetingId", e.sourceMeetingId());
        put(item, "chainId", e.chainId());
        put(item, "causationId", e.causationId());
        put(item, "retentionClass", e.retentionClass());
        if (e.supersedes() != null) {
            item.put("supersedes", AttributeValue.fromN(String.valueOf(e.supersedes())));
        }
        try {
            ddb.putItem(p -> p.tableName(table).item(item).conditionExpression("attribute_not_exists(recordId)"));
            return true;
        } catch (ConditionalCheckFailedException conflict) {
            return false;
        }
    }

    private static void put(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, AttributeValue.fromS(value));
        }
    }

    private static String s(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }
}
