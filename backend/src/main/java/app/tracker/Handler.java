package app.tracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** HTTP API Gateway payload v2 Lambda handler. JWT verification is performed by API Gateway. */
public final class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final ObjectMapper json = new ObjectMapper();
    private final TrackerRepository repository;
    private final CvService cvs;
    private final TailoringJobs jobs;
    private final CognitoIdentityProviderClient cognito;

    public Handler() {
        DynamoDbClient dynamo = DynamoDbClient.create();
        S3Client s3 = S3Client.create();
        repository = new TrackerRepository(dynamo, System.getenv("TABLE_NAME"), json);
        cvs = new CvService(repository, json, s3, S3Presigner.create(), System.getenv("CV_BUCKET_NAME"));
        jobs = new TailoringJobs(repository, cvs, LambdaClient.create(), System.getenv("TAILOR_WORKER_NAME"), json);
        cognito = CognitoIdentityProviderClient.create();
    }

    Handler(TrackerRepository repository, CvService cvs, TailoringJobs jobs,
            CognitoIdentityProviderClient cognito) {
        this.repository = repository;
        this.cvs = cvs;
        this.jobs = jobs;
        this.cognito = cognito;
    }

    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            JsonNode request = json.valueToTree(event);
            String user = request.path("requestContext").path("authorizer").path("jwt")
                    .path("claims").path("sub").asText("");
            if (user.isBlank()) return response(401, message("Not authenticated"));
            String method = request.path("requestContext").path("http").path("method").asText("");
            String path = request.path("rawPath").asText("");
            JsonNode body = body(request);
            if (method.equals("GET") && path.equals("/me")) return response(200, account(request, user));
            if (method.equals("GET") && path.equals("/dashboard")) {
                ObjectNode result = json.createObjectNode();
                result.set("account", account(request, user));
                result.set("applications", repository.list(user, "APP#"));
                return response(200, result);
            }
            if (method.equals("GET") && path.equals("/applications")) {
                ObjectNode result = json.createObjectNode();
                result.set("applications", repository.list(user, "APP#"));
                return response(200, result);
            }
            if (method.equals("POST") && path.equals("/applications")) {
                String id = UUID.randomUUID().toString();
                ObjectNode app = validateApplication(user, id, body, null);
                repository.put(user, "APP#" + id, app);
                return response(201, app);
            }
            if (method.equals("GET") && path.equals("/cvs")) {
                ObjectNode result = json.createObjectNode();
                result.set("cvs", repository.list(user, "CV#"));
                return response(200, result);
            }
            if (method.equals("POST") && path.equals("/cvs/upload-url")) {
                long size = body.path("size").asLong(-1);
                return response(200, cvs.prepareUpload(user, body.path("name").asText(""), size));
            }
            String[] segments = path.split("/");
            if (segments.length == 3 && segments[1].equals("tailoring-jobs") && method.equals("GET"))
                return response(200, jobs.status(user, segments[2]));
            if (segments.length == 3 && segments[1].equals("applications")) {
                String id = Validation.uuid(segments[2]);
                ObjectNode previous = mustGetApp(user, id);
                return switch (method) {
                    case "GET" -> response(200, previous);
                    case "PUT" -> {
                        ObjectNode updated = validateApplication(user, id, body, previous);
                        repository.put(user, "APP#" + id, updated);
                        yield response(200, updated);
                    }
                    case "DELETE" -> {
                        repository.remove(user, "APP#" + id);
                        yield response(200, message("Deleted"));
                    }
                    default -> response(405, message("Method not allowed"));
                };
            }
            if (segments.length == 4 && segments[1].equals("applications") && segments[3].equals("tailor")
                    && method.equals("POST")) {
                String id = Validation.uuid(segments[2]);
                ObjectNode app = mustGetApp(user, id);
                String cvId = Validation.uuid(body.path("cvId").asText(app.path("cvId").asText("")));
                String jobDescription = body.path("jobDescription").asText(app.path("jobDescription").asText(""));
                return response(202, jobs.start(user, id, cvId, jobDescription));
            }
            if (segments.length == 4 && segments[1].equals("cvs") && segments[3].equals("complete")
                    && method.equals("POST"))
                return response(201, cvs.completeUpload(user, segments[2]));
            if (segments.length == 4 && segments[1].equals("cvs") && segments[3].equals("download-url")
                    && method.equals("GET"))
                return response(200, cvs.downloadUrl(user, segments[2]));
            return response(404, message("Route not found"));
        } catch (Validation.BadRequest ex) {
            return response(400, message(ex.getMessage()));
        } catch (NotFound ex) {
            return response(404, message(ex.getMessage()));
        } catch (AiTailorService.UpstreamException ex) {
            return response(502, message(ex.getMessage()));
        } catch (Exception ex) {
            // Do not log request bodies: they can contain CVs, private URLs and job descriptions.
            if (context != null) context.getLogger().log("Tracker API error: " + ex.getClass().getSimpleName());
            return response(500, message("Service error. Please retry."));
        }
    }
    private ObjectNode account(JsonNode request, String user) {
        ObjectNode saved = repository.get(user, "PROFILE");
        if (saved != null) return saved;
        String accessToken = request.path("headers").path("authorization").asText("");
        if (accessToken.isBlank()) accessToken = request.path("headers").path("Authorization").asText("");
        if (!accessToken.startsWith("Bearer ")) throw new Validation.BadRequest("Missing access token");
        var result = cognito.getUser(GetUserRequest.builder().accessToken(accessToken.substring(7)).build());
        ObjectNode account = json.createObjectNode();
        account.put("userId", user);
        account.put("email", "");
        account.put("name", "");
        for (var attr : result.userAttributes()) {
            if (attr.name().equals("email")) account.put("email", attr.value());
            if (attr.name().equals("name")) account.put("name", attr.value());
        }
        account.put("createdAt", TrackerRepository.timestamp());
        repository.put(user, "PROFILE", account);
        return repository.get(user, "PROFILE");
    }
    private ObjectNode mustGetApp(String user, String id) {
        ObjectNode item = repository.get(user, "APP#" + id);
        if (item == null) throw new NotFound("Application not found");
        return item;
    }
    private ObjectNode validateApplication(String user, String id, JsonNode in, ObjectNode previous) {
        if (!in.isObject()) throw new Validation.BadRequest("Expected a JSON application object");
        ObjectNode app = json.createObjectNode();
        app.put("id", id);
        app.put("company", Validation.required(in.path("company").asText(""), "Company", 120));
        app.put("role", Validation.required(in.path("role").asText(""), "Role", 160));
        app.put("jobUrl", Validation.url(in.path("jobUrl").asText("")));
        app.put("status", Validation.status(in.path("status").asText("Saved")));
        app.put("appliedDate", Validation.date(in.path("appliedDate").asText("")));
        app.put("notes", Validation.optional(in.path("notes").asText(""), "Notes", 5000));
        app.put("jobDescription", Validation.optional(in.path("jobDescription").asText(""), "Job description", 15000));
        String cvId = in.path("cvId").asText("");
        if (!cvId.isBlank()) {
            cvId = Validation.uuid(cvId);
            ObjectNode linkedCv = cvs.get(user, cvId);
            app.put("cvId", cvId);
            app.put("cvName", linkedCv.path("name").asText());
        } else { app.put("cvId", ""); app.put("cvName", ""); }
        app.put("createdAt", previous == null ? TrackerRepository.timestamp() : previous.path("createdAt").asText());
        app.put("updatedAt", TrackerRepository.timestamp());
        return app;
    }
    private JsonNode body(JsonNode req) {
        String raw = req.path("body").asText("");
        if (raw.isBlank()) return json.createObjectNode();
        if (req.path("isBase64Encoded").asBoolean(false)) {
            try { raw = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ex) { throw new Validation.BadRequest("Malformed Base64 request body"); }
        }
        try { return json.readTree(raw); }
        catch (Exception ex) { throw new Validation.BadRequest("Malformed JSON request body"); }
    }
    private ObjectNode message(String text) { ObjectNode result = json.createObjectNode(); result.put("message", text); return result; }
    private Map<String, Object> response(int code, JsonNode result) {
        return Map.of("statusCode", code, "headers", Map.of("content-type", "application/json", "cache-control", "no-store"), "body", result.toString());
    }
    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }
}
