package io.stintflow.sandbox.billing.aws;

import java.time.Instant;
import java.util.Map;

import io.stintflow.sandbox.billing.worker.BillingPorts.EffectMarkers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** SDD S.2, RF5: effect markers in {@code billing-effects} (PK {@code effectKey}), written conditionally. */
public final class DynamoDbEffectMarkers implements EffectMarkers {

    private final DynamoDbClient ddb;
    private final String table;

    public DynamoDbEffectMarkers(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    @Override
    public boolean markIfAbsent(String effectKey) {
        try {
            ddb.putItem(r -> r.tableName(table)
                    .item(Map.of("effectKey", AttributeValue.fromS(effectKey),
                            "markedAt", AttributeValue.fromS(Instant.now().toString())))
                    .conditionExpression("attribute_not_exists(effectKey)"));
            return true;
        } catch (ConditionalCheckFailedException alreadyMarked) {
            return false;
        }
    }
}
