package app.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class TrackerRepository {
    private final DynamoDbClient db;
    private final String table;
    private final ObjectMapper json;

    public TrackerRepository(DynamoDbClient db, String table, ObjectMapper json) {
        this.db = db;
        this.table = table;
        this.json = json;
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static String key(String user) { return "USER#" + user; }
    public static String timestamp() { return Instant.now().toString(); }

    public ObjectNode get(String user, String sortKey) {
        GetItemResponse response = db.getItem(GetItemRequest.builder().tableName(table)
                .key(Map.of("pk", s(key(user)), "sk", s(sortKey)))
                .consistentRead(true).build());
        return response.hasItem() ? decode(response.item()) : null;
    }
    public ArrayNode list(String user, String prefix) {
        ArrayNode result = json.createArrayNode();
        Map<String, AttributeValue> cursor = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table)
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                    .expressionAttributeValues(Map.of(":pk", s(key(user)), ":sk", s(prefix)))
                    .consistentRead(true)
                    .scanIndexForward(false);
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            QueryResponse page = db.query(request.build());
            for (Map<String, AttributeValue> item : page.items()) result.add(decode(item));
            cursor = page.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return result;
    }
    public void put(String user, String sortKey, ObjectNode data) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(key(user)));
        item.put("sk", s(sortKey));
        data.fields().forEachRemaining(field -> {
            if (field.getKey().equals("pk") || field.getKey().equals("sk")) return;
            JsonNode val = field.getValue();
            if (!val.isNull()) item.put(field.getKey(), field.getKey().equals("expiresAt")
                    ? AttributeValue.builder().n(val.asText()).build() : s(val.asText()));
        });
        db.putItem(PutItemRequest.builder().tableName(table).item(item).build());
    }
    public void remove(String user, String sortKey) {
        db.deleteItem(DeleteItemRequest.builder().tableName(table)
                .key(Map.of("pk", s(key(user)), "sk", s(sortKey))).build());
    }
    public boolean claimTailoringJob(String user, String id) {
        try {
            db.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(Map.of("pk", s(key(user)), "sk", s("JOB#" + id)))
                    .conditionExpression("#status = :queued")
                    .updateExpression("SET #status = :running, updatedAt = :now")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":queued", s("QUEUED"), ":running", s("RUNNING"), ":now", s(timestamp())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException ex) { return false; }
    }
    private ObjectNode decode(Map<String, AttributeValue> item) {
        ObjectNode obj = json.createObjectNode();
        item.forEach((k, v) -> {
            if (!k.equals("pk") && !k.equals("sk")) {
                if (v.s() != null) obj.put(k, v.s());
                else if (v.n() != null) obj.put(k, v.n());
            }
        });
        return obj;
    }
}
