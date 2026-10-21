package io.stintflow.sandbox.delivery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** SDD S.10, 8e: the parked SDDs in {@code delivery-parked} (key {@code parkKey} = {@code project#cycle#sddId}). */
final class DynamoDbParked implements ParkedSdds {

    private final DynamoDbClient ddb;
    private final String table;

    DynamoDbParked(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    @Override
    public void park(Parked p) {
        ddb.putItem(r -> r.tableName(table).item(Map.of(
                "parkKey", AttributeValue.fromS(ParkedSdds.key(p.projectId(), p.cycle(), p.sddId())),
                "projectId", AttributeValue.fromS(p.projectId()), "cycle", AttributeValue.fromS(p.cycle()),
                "sddId", AttributeValue.fromS(p.sddId()),
                "attempts", AttributeValue.fromN(String.valueOf(p.attempts())))));
    }

    @Override
    public void unpark(String projectId, String cycle, String sddId) {
        ddb.deleteItem(r -> r.tableName(table).key(Map.of("parkKey",
                AttributeValue.fromS(ParkedSdds.key(projectId, cycle, sddId)))));
    }

    @Override
    public List<Parked> all() {
        List<Parked> out = new ArrayList<>();
        for (var page : ddb.scanPaginator(r -> r.tableName(table).consistentRead(true))) {
            for (Map<String, AttributeValue> i : page.items()) {
                out.add(new Parked(i.get("projectId").s(), i.get("cycle").s(), i.get("sddId").s(),
                        Integer.parseInt(i.get("attempts").n())));
            }
        }
        out.sort(Comparator.comparing(Parked::projectId).thenComparing(Parked::cycle).thenComparing(Parked::sddId));
        return out;
    }
}
