package app.tracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationApiTest {
    private TestAws aws;
    @BeforeEach void setup() { aws = new TestAws(); }
    @AfterEach void close() { aws.close(); }
    @Test void everyRouteRequiresVerifiedIdentity() throws Exception {
        for (String path : List.of("/me", "/dashboard", "/applications", "/cvs", "/cvs/upload-url", "/tailoring-jobs/test"))
            aws.request(401, null, "GET", path, null);
        aws.request(401, null, "POST", "/applications", aws.application());
        assertTrue(aws.records.isEmpty());
    }
    @Test void createReadUpdateDeletePreservesEveryApplicationFieldAndOriginalCv() throws Exception {
        var cv = aws.upload("alice", "Submitted.txt", "Student CV with Java experience".getBytes(StandardCharsets.UTF_8));
        var input = aws.application().put("cvId", cv.path("id").asText()).put("cvName", "untrusted-name");
        var created = aws.request(201, "alice", "POST", "/applications", input);
        String path = "/applications/" + created.path("id").asText();
        assertEquals("Submitted.txt", created.path("cvName").asText());
        assertEquals(created, aws.request(200, "alice", "GET", path, null));
        for (String field : List.of("company", "role", "jobUrl", "appliedDate", "notes", "jobDescription", "cvId"))
            assertEquals(input.path(field), created.path(field));
        var changed = created.deepCopy().put("status", "Offer").put("notes", "Accepted");
        var updated = aws.request(200, "alice", "PUT", path, changed);
        assertEquals(created.path("createdAt"), updated.path("createdAt"));
        assertEquals("Accepted", updated.path("notes").asText());
        assertEquals(1, aws.request(200, "alice", "GET", "/applications", null).path("applications").size());
        aws.request(200, "alice", "DELETE", path, null);
        aws.request(404, "alice", "GET", path, null);
        assertEquals(0, aws.request(200, "alice", "GET", "/applications", null).path("applications").size());
        assertEquals(cv, aws.cvs.get("alice", cv.path("id").asText()));
    }
    @ParameterizedTest @ValueSource(strings={"Saved","Applied","Assessment","Interview","Offer","Rejected","Withdrawn"})
    void eachStatusCanBeStoredAndRetrieved(String status) throws Exception {
        var created = aws.request(201, "alice", "POST", "/applications", aws.application().put("status", status));
        assertEquals(status, aws.request(200, "alice", "GET", "/applications/" + created.path("id").asText(), null).path("status").asText());
    }
    @Test void anotherUserCannotReadChangeDeleteOrAttachOwnedRecords() throws Exception {
        var cv = aws.upload("alice", "CV.txt", new byte[]{1,2,3});
        var app = aws.request(201, "alice", "POST", "/applications", aws.application().put("cvId", cv.path("id").asText()));
        for (String method : List.of("GET", "PUT", "DELETE"))
            aws.request(404, "bob", method, "/applications/" + app.path("id").asText(), aws.application());
        aws.request(404, "bob", "POST", "/applications", aws.application().put("cvId", cv.path("id").asText()));
        aws.request(404, "bob", "GET", "/cvs/" + cv.path("id").asText() + "/download-url", null);
        assertEquals(0, aws.request(200, "bob", "GET", "/applications", null).path("applications").size());
    }
    @Test void invalidFieldsDoNotWritePartialRecords() throws Exception {
        for (var entry : Map.of("company", " ", "role", "x".repeat(161), "jobUrl", "javascript:alert(1)",
                "status", "unknown", "appliedDate", "20260812", "notes", "x".repeat(5001), "jobDescription", "x".repeat(15001)).entrySet())
            aws.request(400, "alice", "POST", "/applications", aws.application().put(entry.getKey(), entry.getValue()));
        assertTrue(aws.records.isEmpty());
    }
    @Test void dateCanBeLeapDayOrClearedAndCvCanBeDetached() throws Exception {
        var cv = aws.upload("alice", "CV.txt", new byte[]{1});
        var created = aws.request(201, "alice", "POST", "/applications", aws.application().put("appliedDate", "2028-02-29").put("cvId", cv.path("id").asText()));
        var updated = aws.request(200, "alice", "PUT", "/applications/" + created.path("id").asText(), created.put("appliedDate", "").put("cvId", ""));
        assertEquals("", updated.path("appliedDate").asText());
        assertEquals("", updated.path("cvName").asText());
    }
    @Test void malformedJsonAndUnsupportedMethodsHaveUsefulHttpStatuses() throws Exception {
        var malformed = aws.handler.handleRequest(aws.event("alice", "POST", "/applications", "{"), null);
        assertEquals(400, malformed.get("statusCode"));
        var created = aws.request(201, "alice", "POST", "/applications", aws.application());
        aws.request(405, "alice", "PATCH", "/applications/" + created.path("id").asText(), null);
        aws.request(404, "alice", "GET", "/missing", null);
        aws.request(400, "alice", "GET", "/applications/not-an-id", null);
    }
    @Test void base64PayloadIsDecodedAndInvalidEncodingIsAClientError() throws Exception {
        var event = new HashMap<>(aws.event("alice", "POST", "/applications", Base64.getEncoder().encodeToString(aws.application().toString().getBytes(StandardCharsets.UTF_8))));
        event.put("isBase64Encoded", true);
        assertEquals(201, aws.handler.handleRequest(event, null).get("statusCode"));
        event.put("body", "%%%invalid%%%");
        assertEquals(400, aws.handler.handleRequest(event, null).get("statusCode"));
    }
}
