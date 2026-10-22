package io.stintflow.sandbox.orders.aws;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import io.stintflow.sandbox.orders.worker.OrdersPorts.EffectMarkers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** SDD S.3, RF4: effect markers in {@code orders-effects} (PK {@code effectKey}), written conditionally. */
public final class DynamoDbEffectMarkers implements EffectMarkers {

    private final DynamoDbClient ddb;
    private final String table;

    public DynamoDbEffectMarkers(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    @Override
    public Optional<Instant> markIfAbsent(String effectKey, Instant now) {
        try {
            ddb.putItem(r -> r.tableName(table)
                    .item(Map.of("effectKey", AttributeValue.fromS(effectKey), "markedAt", AttributeValue.fromS(now.toString())))
                    .conditionExpression("attribute_not_exists(effectKey)"));
            return Optional.empty();
        } catch (ConditionalCheckFailedException alreadyMarked) {
            Map<String, AttributeValue> item = ddb.getItem(r -> r.tableName(table).consistentRead(true)
                    .key(Map.of("effectKey", AttributeValue.fromS(effectKey)))).item();
            return Optional.of(Instant.parse(item.get("markedAt").s()));
        }
    }
}
