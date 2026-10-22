package io.stintflow.sandbox.records;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** SDD S.7, 8c: the approvals in {@code records-approvals} (key {@code recordKey} = {@code recordId#version} + {@code reviewId}). */
public final class DynamoDbApprovals implements ApproveRecordHandler.Approvals {

    private final DynamoDbClient ddb;
    private final String table;

    public DynamoDbApprovals(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    private static String key(String recordId, int version) {
        return recordId + "#" + version;
    }

    @Override
    public Optional<Approval> putIfAbsent(Approval a) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("recordKey", AttributeValue.fromS(key(a.recordId(), a.version())));
        item.put("reviewId", AttributeValue.fromS(a.reviewId()));
        item.put("approver", AttributeValue.fromS(a.approver()));
        item.put("decision", AttributeValue.fromS(a.decision()));
        if (a.note() != null) {
            item.put("note", AttributeValue.fromS(a.note()));
        }
        try {
            ddb.putItem(p -> p.tableName(table).item(item).conditionExpression("attribute_not_exists(recordKey)"));
            return Optional.empty();
        } catch (ConditionalCheckFailedException already) {
            var stored = ddb.getItem(g -> g.tableName(table).consistentRead(true).key(Map.of("recordKey",
                    item.get("recordKey"), "reviewId", item.get("reviewId")))).item();
            return Optional.of(of(a.recordId(), a.version(), stored));
        }
    }

    @Override
    public List<Approval> of(String recordId, int version) {
        return ddb.query(q -> q.tableName(table).keyConditionExpression("recordKey = :k").consistentRead(true)
                .expressionAttributeValues(Map.of(":k", AttributeValue.fromS(key(recordId, version))))).items().stream()
                .map(i -> of(recordId, version, i)).toList();
    }

    private static Approval of(String recordId, int version, Map<String, AttributeValue> i) {
        return new Approval(recordId, version, i.get("reviewId").s(), i.get("approver").s(), i.get("decision").s(),
                i.get("note") == null ? null : i.get("note").s());
    }
}
