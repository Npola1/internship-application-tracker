package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.lang.reflect.Proxy;
import java.util.*;

/** Only the AWS boundaries are substituted. Handler, persistence serialization and CV processing are real. */
final class TestAws implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper();
    final Map<Map<String, AttributeValue>, Map<String, AttributeValue>> records = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();
    final Map<String, String> mime = new HashMap<>();
    final List<QueryRequest> queries = new ArrayList<>();
    int pageSize = Integer.MAX_VALUE;
    final DynamoDbClient db = (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),
            new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> switch (method.getName()) {
        case "getItem" -> {
            var request = (GetItemRequest) args[0];
            if (!request.consistentRead()) throw new AssertionError("Expected consistent read");
            yield GetItemResponse.builder().item(records.get(request.key())).build();
        }
        case "putItem" -> {
            var item = Map.copyOf(((PutItemRequest) args[0]).item());
            records.put(key(item), item);
            yield PutItemResponse.builder().build();
        }
        case "deleteItem" -> { records.remove(((DeleteItemRequest) args[0]).key()); yield DeleteItemResponse.builder().build(); }
        case "query" -> {
            var request = (QueryRequest) args[0];
            queries.add(request);
            var values = request.expressionAttributeValues();
            var matches = records.values().stream().filter(r -> r.get("pk").equals(values.get(":pk"))
                    && r.get("sk").s().startsWith(values.get(":sk").s()))
                    .sorted(Comparator.comparing((Map<String, AttributeValue> r) -> r.get("sk").s()).reversed()).toList();
            int start = 0;
            if (request.hasExclusiveStartKey()) {
                for (int i = 0; i < matches.size(); i++) if (key(matches.get(i)).equals(request.exclusiveStartKey())) start = i + 1;
            }
            int end = (int) Math.min(matches.size(), (long) start + pageSize);
            var result = QueryResponse.builder().items(matches.subList(start, end));
            if (end < matches.size()) result.lastEvaluatedKey(key(matches.get(end - 1)));
            yield result.build();
        }
        default -> throw new AssertionError("Unexpected DynamoDB call: " + method.getName());
    });
    final S3Client s3 = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
            (proxy, method, args) -> switch (method.getName()) {
        case "headObject" -> {
            String key = ((HeadObjectRequest) args[0]).key();
            if (!files.containsKey(key)) throw S3Exception.builder().statusCode(404).build();
            yield HeadObjectResponse.builder().contentLength((long) files.get(key).length).contentType(mime.get(key)).build();
        }
        case "getObjectAsBytes" -> {
            String key = ((GetObjectRequest) args[0]).key();
            if (!files.containsKey(key)) throw S3Exception.builder().statusCode(404).build();
            yield ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), files.get(key));
        }
        case "putObject" -> {
            var request = (PutObjectRequest) args[0];
            try (var input = ((RequestBody) args[1]).contentStreamProvider().newStream()) { files.put(request.key(), input.readAllBytes()); }
            mime.put(request.key(), request.contentType());
            yield PutObjectResponse.builder().build();
        }
        default -> throw new AssertionError("Unexpected S3 call: " + method.getName());
    });
    final S3Presigner signer = S3Presigner.builder().region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-only", "test-only"))).build();
    final TrackerRepository repo = new TrackerRepository(db, "test-table", json);
    final CvService cvs = new CvService(repo, json, s3, signer, "test-private-cvs");
    final Handler handler = new Handler(repo, cvs, null, null);
    private static Map<String, AttributeValue> key(Map<String, AttributeValue> item) { return Map.of("pk", item.get("pk"), "sk", item.get("sk")); }
    ObjectNode application() { return json.createObjectNode().put("company", "Student Employer").put("role", "Java Intern")
            .put("jobUrl", "https://example.org/internships/1").put("status", "Saved").put("appliedDate", "2026-08-12")
            .put("notes", "Follow up next week").put("jobDescription", "Java and unit testing internship"); }
    Map<String, Object> event(String user, String method, String path, String body) {
        return Map.of("rawPath", path, "body", body, "requestContext", Map.of("http", Map.of("method", method),
                "authorizer", Map.of("jwt", Map.of("claims", user == null ? Map.of() : Map.of("sub", user)))));
    }
    ObjectNode request(int status, String user, String method, String path, ObjectNode body) throws Exception {
        var result = handler.handleRequest(event(user, method, path, body == null ? "" : body.toString()), null);
        org.junit.jupiter.api.Assertions.assertEquals(status, result.get("statusCode"), result.toString());
        org.junit.jupiter.api.Assertions.assertEquals("no-store", ((Map<?, ?>) result.get("headers")).get("cache-control"));
        return (ObjectNode) json.readTree(result.get("body").toString());
    }
    ObjectNode upload(String user, String name, byte[] bytes) {
        var prepared = cvs.prepareUpload(user, name, bytes.length);
        var pending = repo.get(user, "UPLOAD#" + prepared.path("id").asText());
        files.put(pending.path("objectKey").asText(), bytes);
        mime.put(pending.path("objectKey").asText(), prepared.path("contentType").asText());
        return cvs.completeUpload(user, prepared.path("id").asText());
    }
    @Override public void close() { signer.close(); }
}
