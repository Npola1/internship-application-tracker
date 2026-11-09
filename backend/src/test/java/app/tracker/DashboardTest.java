package app.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real handler/repository serialization with in-memory AWS client substitutes. */
class DashboardTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Map<String, AttributeValue>, Map<String, AttributeValue>> items = new HashMap<>();
    private TrackerRepository repository;
    private CognitoIdentityProviderClient cognito;
    private int cognitoReads;
    private boolean cognitoUnavailable;

    @BeforeEach void setUp() {
        DynamoDbClient dynamo = (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(), new Class<?>[]{DynamoDbClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> {
                        GetItemRequest request = (GetItemRequest) args[0];
                        assertTrue(request.consistentRead());
                        yield GetItemResponse.builder().item(items.get(request.key())).build();
                    }
                    case "putItem" -> {
                        PutItemRequest request = (PutItemRequest) args[0];
                        Map<String, AttributeValue> record = Map.copyOf(request.item());
                        items.put(Map.of("pk", record.get("pk"), "sk", record.get("sk")), record);
                        yield PutItemResponse.builder().build();
                    }
                    case "query" -> {
                        QueryRequest request = (QueryRequest) args[0];
                        assertTrue(request.consistentRead());
                        AttributeValue partition = request.expressionAttributeValues().get(":pk");
                        String prefix = request.expressionAttributeValues().get(":sk").s();
                        List<Map<String, AttributeValue>> matches = items.values().stream()
                                .filter(record -> record.get("pk").equals(partition)
                                        && record.get("sk").s().startsWith(prefix)).toList();
                        yield QueryResponse.builder().items(matches).build();
                    }
                    default -> throw new AssertionError("Unexpected DynamoDB call: " + method.getName());
                });
        repository = new TrackerRepository(dynamo, "test-table", json);
        cognito = (CognitoIdentityProviderClient) Proxy.newProxyInstance(
                CognitoIdentityProviderClient.class.getClassLoader(), new Class<?>[]{CognitoIdentityProviderClient.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getUser"))
                        throw new AssertionError("Unexpected Cognito call: " + method.getName());
                    cognitoReads++;
                    if (cognitoUnavailable) throw new IllegalStateException("Cognito unavailable");
                    String user = ((GetUserRequest) args[0]).accessToken().substring("token-".length());
                    return GetUserResponse.builder().username(user).userAttributes(
                            attribute -> attribute.name("email").value(user + "@example.org"),
                            attribute -> attribute.name("name").value("Student " + user),
                            attribute -> attribute.name("custom:private").value("not stored")
                    ).build();
                });
    }

    private Handler handler() { return new Handler(repository, null, null, cognito); }

    private Map<String, Object> event(String user, String path) {
        return Map.of("rawPath", path, "headers", Map.of("authorization", "Bearer token-" + user),
                "requestContext", Map.of("http", Map.of("method", "GET"),
                        "authorizer", Map.of("jwt", Map.of("claims", Map.of("sub", user)))));
    }

    private JsonNode body(Map<String, Object> response) throws Exception {
        assertEquals(200, response.get("statusCode"), response.toString());
        return json.readTree((String) response.get("body"));
    }

    @Test void dashboardPersistsAccountAndShowsAllFourRequiredCategories() throws Exception {
        ObjectNode app = json.createObjectNode().put("id", "application-1")
                .put("company", "Example Employer").put("role", "Software Intern")
                .put("jobUrl", "https://example.org/internship").put("cvId", "cv-1").put("cvName", "submitted.docx");
        repository.put("alice", "APP#application-1", app);

        JsonNode dashboard = body(handler().handleRequest(event("alice", "/dashboard"), null));
        assertEquals(app, dashboard.path("applications").get(0));
        assertEquals("alice@example.org", dashboard.path("account").path("email").asText());
        assertEquals("Student alice", dashboard.path("account").path("name").asText());
        assertEquals(dashboard.path("account"), repository.get("alice", "PROFILE"));
        assertFalse(items.values().toString().contains("token-"));
        assertFalse(items.values().toString().contains("custom:private"));
    }

    @Test void newHandlerUsesPersistedProfileBetweenSessions() throws Exception {
        JsonNode initial = body(handler().handleRequest(event("alice", "/dashboard"), null));
        cognitoUnavailable = true;
        JsonNode later = body(handler().handleRequest(event("alice", "/dashboard"), null));
        assertEquals(initial.path("account"), later.path("account"));
        assertEquals(1, cognitoReads);
    }

    @Test void dashboardSeparatesUsersAndRecordTypes() throws Exception {
        repository.put("alice", "APP#alice-app", json.createObjectNode().put("id", "alice-app"));
        repository.put("bob", "APP#bob-app", json.createObjectNode().put("id", "bob-app"));
        repository.put("alice", "CV#private-cv", json.createObjectNode().put("id", "private-cv"));
        JsonNode alice = body(handler().handleRequest(event("alice", "/dashboard"), null));
        JsonNode bob = body(handler().handleRequest(event("bob", "/dashboard"), null));
        assertEquals(1, alice.path("applications").size());
        assertEquals("alice-app", alice.path("applications").get(0).path("id").asText());
        assertEquals("bob-app", bob.path("applications").get(0).path("id").asText());
        assertEquals("alice", alice.path("account").path("userId").asText());
        assertEquals("bob", bob.path("account").path("userId").asText());
    }

    @Test void profileRouteReturnsSamePersistedAccount() throws Exception {
        JsonNode profile = body(handler().handleRequest(event("alice", "/me"), null));
        JsonNode dashboard = body(handler().handleRequest(event("alice", "/dashboard"), null));
        assertEquals(profile, dashboard.path("account"));
        assertEquals(1, cognitoReads);
    }

    @Test void rejectsDashboardWithoutVerifiedIdentity() {
        Map<String, Object> response = handler().handleRequest(Map.of("rawPath", "/dashboard"), null);
        assertEquals(401, response.get("statusCode"));
        assertTrue(items.isEmpty());
        assertEquals(0, cognitoReads);
    }

    @Test void failedProfileBootstrapDoesNotPersistPartialAccount() {
        cognitoUnavailable = true;
        Map<String, Object> response = handler().handleRequest(event("alice", "/dashboard"), null);
        assertEquals(500, response.get("statusCode"));
        assertNull(repository.get("alice", "PROFILE"));
        assertFalse(response.get("body").toString().contains("Cognito unavailable"));
    }
}
