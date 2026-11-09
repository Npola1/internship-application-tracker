package app.tracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.Map;

public final class TailorWorker implements RequestHandler<Map<String, Object>, Void> {
    @FunctionalInterface interface TailoringOperation {
        String tailor(String cv, String description) throws Exception;
    }
    private final TrackerRepository repository;
    private final CvService cvs;
    private final TailoringOperation operation;

    public TailorWorker() {
        ObjectMapper json = new ObjectMapper();
        repository = new TrackerRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME"), json);
        cvs = new CvService(repository, json, S3Client.create(), S3Presigner.create(), System.getenv("CV_BUCKET_NAME"));
        AiTailorService ai = new AiTailorService(json, SecretsManagerClient.create(), System.getenv("OPENAI_SECRET_ARN"),
                System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5-mini"));
        operation = ai::tailor;
    }

    TailorWorker(TrackerRepository repository, CvService cvs, TailoringOperation operation) {
        this.repository = repository;
        this.cvs = cvs;
        this.operation = operation;
    }

    @Override public Void handleRequest(Map<String, Object> event, Context context) {
        if (!(event.get("user") instanceof String user) || user.isBlank())
            throw new IllegalArgumentException("Missing worker identity");
        String id = Validation.uuid(String.valueOf(event.get("jobId")));
        ObjectNode job = repository.get(user, "JOB#" + id);
        if (job == null || !repository.claimTailoringJob(user, id)) return null;
        try {
            String sourceId = job.path("sourceCvId").asText();
            ObjectNode source = cvs.get(user, sourceId);
            String original = cvs.text(cvs.bytes(user, sourceId), source.path("mimeType").asText());
            String output = operation.tailor(original, job.path("jobDescription").asText());
            ObjectNode generated = cvs.storeTailored(user, job.path("applicationId").asText(), sourceId, output);
            job.put("status", "COMPLETED").put("resultCvId", generated.path("id").asText())
                    .put("preview", output.substring(0, Math.min(2200, output.length())));
        } catch (Exception ex) {
            String message = ex instanceof Validation.BadRequest || ex instanceof AiTailorService.UpstreamException
                    ? ex.getMessage() : "CV tailoring failed. Please try again.";
            job.put("status", "FAILED").put("message", message);
            if (context != null) context.getLogger().log("Tailoring error: " + ex.getClass().getSimpleName());
        }
        job.remove("jobDescription");
        job.put("updatedAt", TrackerRepository.timestamp());
        repository.put(user, "JOB#" + id, job);
        return null;
    }
}
