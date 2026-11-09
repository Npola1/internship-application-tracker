package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

/** Queues slow AI work outside API Gateway's request timeout; all state belongs to the verified user. */
public final class TailoringJobs {
    private final TrackerRepository repository;
    private final CvService cvs;
    private final LambdaClient lambda;
    private final String worker;
    private final ObjectMapper json;

    public TailoringJobs(TrackerRepository repository, CvService cvs, LambdaClient lambda, String worker, ObjectMapper json) {
        this.repository = repository;
        this.cvs = cvs;
        this.lambda = lambda;
        this.worker = worker;
        this.json = json;
    }

    public ObjectNode start(String user, String applicationId, String cvId, String description) {
        cvs.get(user, Validation.uuid(cvId));
        description = Validation.required(description, "Job description", 15000);
        String id = UUID.randomUUID().toString();
        ObjectNode job = json.createObjectNode().put("id", id).put("applicationId", applicationId)
                .put("sourceCvId", cvId).put("jobDescription", description).put("status", "QUEUED")
                .put("createdAt", TrackerRepository.timestamp()).put("updatedAt", TrackerRepository.timestamp())
                .put("expiresAt", Instant.now().plus(Duration.ofDays(1)).getEpochSecond());
        repository.put(user, "JOB#" + id, job);
        ObjectNode event = json.createObjectNode().put("user", user).put("jobId", id);
        try {
            var result = lambda.invoke(InvokeRequest.builder().functionName(worker).invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(event.toString())).build());
            if (result.statusCode() != 202) throw new IllegalStateException("Worker did not accept the request");
        } catch (RuntimeException ex) {
            job.put("status", "FAILED").put("message", "Could not queue CV tailoring. Please retry.");
            job.remove("jobDescription");
            repository.put(user, "JOB#" + id, job);
            throw ex;
        }
        return json.createObjectNode().put("jobId", id).put("status", "QUEUED");
    }

    public ObjectNode status(String user, String id) {
        ObjectNode job = repository.get(user, "JOB#" + Validation.uuid(id));
        if (job == null) throw new Handler.NotFound("Tailoring request not found");
        if (("QUEUED".equals(job.path("status").asText()) || "RUNNING".equals(job.path("status").asText()))
                && Instant.parse(job.path("createdAt").asText()).plus(Duration.ofMinutes(10)).isBefore(Instant.now())) {
            job.put("status", "FAILED").put("message", "CV tailoring timed out. Please try again.");
            job.remove("jobDescription");
            repository.put(user, "JOB#" + id, job);
        }
        ObjectNode result = json.createObjectNode().put("jobId", id).put("status", job.path("status").asText())
                .put("applicationId", job.path("applicationId").asText());
        if ("COMPLETED".equals(job.path("status").asText())) {
            result.set("tailoredCv", cvs.get(user, job.path("resultCvId").asText()));
            result.put("preview", job.path("preview").asText());
            result.put("reviewRequired", true);
        } else if ("FAILED".equals(job.path("status").asText())) {
            result.put("message", job.path("message").asText("CV tailoring failed. Please retry."));
        }
        return result;
    }
}
