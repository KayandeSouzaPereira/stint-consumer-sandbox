package io.stintflow.sandbox.billing.aws;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import io.stintflow.sandbox.billing.worker.BillingPorts.Invoice;
import io.stintflow.sandbox.billing.worker.BillingPorts.InvoiceRecords;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;

/**
 * SDD S.2, 8c: the {@code billing-invoices} table (PK {@code invoiceId}). The write is conditional, so the first
 * record of an invoice wins and keeps its {@code issuedAt}. The window count is a filtered {@code Scan} on
 * {@code issuedAtEpochMs}, enough at the sandbox's scale.
 */
public final class DynamoDbInvoiceRecords implements InvoiceRecords {

    private final DynamoDbClient ddb;
    private final String table;

    public DynamoDbInvoiceRecords(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    @Override
    public Invoice recordIfAbsent(Invoice invoice) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("invoiceId", s(invoice.invoiceId()));
        item.put("orderId", s(invoice.orderId()));
        item.put("amount", n(invoice.amount().toPlainString()));
        item.put("currency", s(invoice.currency()));
        item.put("issuedAt", s(invoice.issuedAt().toString()));
        item.put("issuedAtEpochMs", n(Long.toString(invoice.issuedAt().toEpochMilli())));
        try {
            ddb.putItem(r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(invoiceId)"));
            return invoice;
        } catch (ConditionalCheckFailedException alreadyRecorded) {
            Map<String, AttributeValue> stored = ddb.getItem(r -> r.tableName(table).consistentRead(true)
                    .key(Map.of("invoiceId", s(invoice.invoiceId())))).item();
            return new Invoice(stored.get("invoiceId").s(), stored.get("orderId").s(),
                    new BigDecimal(stored.get("amount").n()), stored.get("currency").s(),
                    Instant.parse(stored.get("issuedAt").s()));
        }
    }

    @Override
    public long countIssued(Instant from, Instant to) {
        long count = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            Map<String, AttributeValue> exclusiveStart = startKey;
            ScanResponse page = ddb.scan(r -> r.tableName(table).select(Select.COUNT).consistentRead(true)
                    .filterExpression("issuedAtEpochMs >= :from AND issuedAtEpochMs < :to")
                    .expressionAttributeValues(Map.of(":from", n(Long.toString(from.toEpochMilli())),
                            ":to", n(Long.toString(to.toEpochMilli()))))
                    .exclusiveStartKey(exclusiveStart));
            count += page.count();
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return count;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(String value) {
        return AttributeValue.fromN(value);
    }
}
