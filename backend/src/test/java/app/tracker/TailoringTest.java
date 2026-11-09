package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TailoringTest {
    private static final String APP = "11111111-1111-4111-8111-111111111111";
    private static final String CV = "22222222-2222-4222-8222-222222222222";
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Map<String, AttributeValue>, Map<String, AttributeValue>> records = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private TrackerRepository repository;
    private CvService cvs;
    private TailoringJobs jobs;
    private InvokeRequest invocation;
    private boolean enqueueFails;

    @BeforeEach void setUp() {
        DynamoDbClient dynamo = (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> GetItemResponse.builder().item(records.get(((GetItemRequest) args[0]).key())).build();
                    case "putItem" -> {
                        var record = Map.copyOf(((PutItemRequest) args[0]).item());
                        records.put(Map.of("pk", record.get("pk"), "sk", record.get("sk")), record);
                        yield PutItemResponse.builder().build();
                    }
                    case "updateItem" -> {
                        var request = (UpdateItemRequest) args[0];
                        var record = records.get(request.key());
                        if (record == null || !"QUEUED".equals(record.get("status").s()))
                            throw ConditionalCheckFailedException.builder().message("Already claimed").build();
                        assertEquals("#status = :queued", request.conditionExpression());
                        var updated = new HashMap<>(record);
                        updated.put("status", request.expressionAttributeValues().get(":running"));
                        updated.put("updatedAt", request.expressionAttributeValues().get(":now"));
                        records.put(request.key(), updated);
                        yield UpdateItemResponse.builder().build();
                    }
                    case "query" -> {
                        var request = (QueryRequest) args[0];
                        var partition = request.expressionAttributeValues().get(":pk");
                        var prefix = request.expressionAttributeValues().get(":sk").s();
                        yield QueryResponse.builder().items(records.values().stream()
                                .filter(r -> r.get("pk").equals(partition) && r.get("sk").s().startsWith(prefix)).toList()).build();
                    }
                    default -> throw new AssertionError("Unexpected DynamoDB operation: " + method.getName());
                });
        repository = new TrackerRepository(dynamo, "test-table", json);
        S3Client s3 = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getObjectAsBytes" -> ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                            files.get(((GetObjectRequest) args[0]).key()));
                    case "putObject" -> {
                        try (var input = ((RequestBody) args[1]).contentStreamProvider().newStream()) {
                            files.put(((PutObjectRequest) args[0]).key(), input.readAllBytes());
                        }
                        yield PutObjectResponse.builder().build();
                    }
                    default -> throw new AssertionError("Unexpected S3 operation: " + method.getName());
                });
        cvs = new CvService(repository, json, s3, null, "test-cvs");
        LambdaClient lambda = (LambdaClient) Proxy.newProxyInstance(LambdaClient.class.getClassLoader(),
                new Class<?>[]{LambdaClient.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("invoke")) throw new AssertionError(method.getName());
                    invocation = (InvokeRequest) args[0];
                    if (enqueueFails) throw new IllegalStateException("Queue unavailable");
                    return InvokeResponse.builder().statusCode(202).build();
                });
        jobs = new TailoringJobs(repository, cvs, lambda, "test-worker", json);
        repository.put("alice", "APP#" + APP, json.createObjectNode().put("id", APP).put("cvId", CV)
                .put("jobDescription", "A Java software engineering internship"));
        repository.put("alice", "CV#" + CV, json.createObjectNode().put("id", CV).put("name", "Original.txt")
                .put("mimeType", "text/plain").put("objectKey", "users/alice/source.txt"));
        files.put("users/alice/source.txt", "DEMO STUDENT\nSkills: Java\nProject: Built a Java application tracker."
                .getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> workerEvent() throws Exception {
        return json.readValue(invocation.payload().asUtf8String(), Map.class);
    }

    @Test void queuesWithoutBlockingAndKeepsPrivateTextOutOfInvocation() throws Exception {
        var result = jobs.start("alice", APP, CV, "A Java software engineering internship");
        assertEquals("QUEUED", result.path("status").asText());
        assertEquals(InvocationType.EVENT, invocation.invocationType());
        assertEquals("test-worker", invocation.functionName());
        assertEquals(2, workerEvent().size());
        assertEquals("alice", workerEvent().get("user"));
        var status = jobs.status("alice", result.path("jobId").asText());
        assertFalse(status.has("jobDescription"));
        assertFalse(status.has("sourceCvId"));
        assertThrows(Handler.NotFound.class, () -> jobs.status("bob", result.path("jobId").asText()));
    }

    @Test void workerProducesRealDocxAndDuplicateDeliveryDoesNotRepeatAiCall() throws Exception {
        String output = "DEMO STUDENT\nSKILLS\n- Java\nPROJECTS\n- Built a Java internship application tracker.";
        var count = new AtomicInteger();
        var started = jobs.start("alice", APP, CV, "A Java internship");
        var worker = new TailorWorker(repository, cvs, (original, description) -> {
            assertTrue(original.contains("Skills: Java"));
            assertEquals("A Java internship", description);
            count.incrementAndGet();
            return output;
        });
        worker.handleRequest(workerEvent(), null);
        worker.handleRequest(workerEvent(), null);
        assertEquals(1, count.get());
        var result = jobs.status("alice", started.path("jobId").asText());
        assertEquals("COMPLETED", result.path("status").asText());
        assertTrue(result.path("reviewRequired").asBoolean());
        assertEquals(output, result.path("preview").asText());
        var generated = result.path("tailoredCv");
        assertEquals(CV, generated.path("sourceCvId").asText());
        assertEquals(APP, generated.path("applicationId").asText());
        try (var document = new XWPFDocument(new ByteArrayInputStream(files.get(generated.path("objectKey").asText())))) {
            assertEquals("DEMO STUDENT", document.getParagraphs().getFirst().getText());
        }
    }

    @Test void workerFailuresAreVisibleAndDoNotCreatePartialCvRecords() throws Exception {
        var started = jobs.start("alice", APP, CV, "A Java internship");
        new TailorWorker(repository, cvs, (original, description) -> {
            throw new IllegalStateException("private upstream detail");
        }).handleRequest(workerEvent(), null);
        var result = jobs.status("alice", started.path("jobId").asText());
        assertEquals("FAILED", result.path("status").asText());
        assertFalse(result.toString().contains("private upstream detail"));
        assertFalse(result.has("tailoredCv"));
        assertEquals(1, repository.list("alice", "CV#").size());
    }

    @Test void enqueueFailureDoesNotLeaveAQueuedJob() {
        enqueueFails = true;
        assertThrows(IllegalStateException.class, () -> jobs.start("alice", APP, CV, "A Java internship"));
        assertEquals("FAILED", repository.list("alice", "JOB#").get(0).path("status").asText());
    }

    @Test void billingFailureIsReadableThroughTheJobWithoutCreatingATailoredCv() throws Exception {
        var started = jobs.start("alice", APP, CV, "A Java internship");
        String message = AiTailorService.failureMessage(json, 429,
                "{\"error\":{\"code\":\"credit_balance_exhausted\",\"type\":\"insufficient_quota\"}}");
        new TailorWorker(repository, cvs, (original, description) -> {
            throw new AiTailorService.UpstreamException(message);
        }).handleRequest(workerEvent(), null);
        var result = jobs.status("alice", started.path("jobId").asText());
        assertEquals("FAILED", result.path("status").asText());
        assertEquals(message, result.path("message").asText());
        assertFalse(result.has("tailoredCv"));
        assertEquals(1, repository.list("alice", "CV#").size());
    }

    @Test void expiredRunningJobCanBeRetriedRatherThanStayingPendingForever() {
        var started = jobs.start("alice", APP, CV, "A Java internship");
        String id = started.path("jobId").asText();
        ObjectNode record = repository.get("alice", "JOB#" + id);
        record.put("status", "RUNNING").put("createdAt", Instant.now().minus(Duration.ofMinutes(11)).toString());
        repository.put("alice", "JOB#" + id, record);
        assertEquals("FAILED", jobs.status("alice", id).path("status").asText());
    }

    @Test void handlerStartsWith202AndExposesOnlyTheOwnersJob() throws Exception {
        Handler handler = new Handler(repository, cvs, jobs, null);
        Map<String, Object> event = Map.of("rawPath", "/applications/" + APP + "/tailor", "body", "{}",
                "requestContext", Map.of("http", Map.of("method", "POST"), "authorizer", Map.of("jwt", Map.of("claims", Map.of("sub", "alice")))));
        var response = handler.handleRequest(event, null);
        assertEquals(202, response.get("statusCode"));
        assertTrue(json.readTree((String) response.get("body")).has("jobId"));
    }

    @Test void readsBothPlainTextAndAwsConsoleJsonSecretsWithoutLeakingInvalidValues() {
        assertEquals("test-key", AiTailorService.apiKeyFromSecret(json, "  test-key  "));
        assertEquals("test-key", AiTailorService.apiKeyFromSecret(json, "{\"OPENAI_API_KEY\":\"test-key\"}"));
        assertEquals("test-key", AiTailorService.apiKeyFromSecret(json, "{\"api_key\":\"test-key\"}"));
        var error = assertThrows(IllegalStateException.class,
                () -> AiTailorService.apiKeyFromSecret(json, "{\"unknown\":\"private-key\"}"));
        assertFalse(error.getMessage().contains("private-key"));
    }
}
