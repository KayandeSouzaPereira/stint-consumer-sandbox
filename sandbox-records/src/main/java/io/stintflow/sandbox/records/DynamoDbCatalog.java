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
        Map<String, AttributeValue> i = items.get(0);
        return Optional.of(new Entry(s(i, "recordId"), Integer.parseInt(i.get("version").n()), s(i, "sha256"),
                s(i, "kind"), s(i, "ownerRole"), s(i, "sensitivity"), s(i, "storedRef"), s(i, "projectId"),
                s(i, "cycle")));
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
        try {
            ddb.putItem(p -> p.tableName(table).item(item).conditionExpression("attribute_not_exists(recordId)"));
            return true;
        } catch (ConditionalCheckFailedException conflict) {
            return false;
        }
    }

    private static String s(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }
}
