package app.tracker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    @Test void allPagesAreReturnedWithoutCrossingUserOrRecordTypeBoundaries() {
        try (var aws = new TestAws()) {
            aws.pageSize = 1;
            for (int i=0;i<3;i++) aws.repo.put("alice", "APP#"+i, aws.json.createObjectNode().put("id", "app-"+i));
            aws.repo.put("bob", "APP#other", aws.json.createObjectNode().put("id", "other"));
            aws.repo.put("alice", "CV#other", aws.json.createObjectNode().put("id", "cv"));
            var apps=aws.repo.list("alice", "APP#");
            assertEquals(3, apps.size());
            assertEquals("app-2", apps.get(0).path("id").asText());
            assertEquals("app-0", apps.get(2).path("id").asText());
            assertEquals(3, aws.queries.size());
            assertTrue(aws.queries.stream().allMatch(q -> q.consistentRead()));
        }
    }
    @Test void callerDataCannotOverwriteOwnershipKeysAndTtlIsNumeric() {
        try (var aws=new TestAws()) {
            aws.repo.put("alice", "JOB#one", aws.json.createObjectNode().put("pk", "USER#bob").put("sk", "APP#stolen").put("expiresAt", 1900000000));
            var raw=aws.records.values().iterator().next();
            assertEquals("USER#alice", raw.get("pk").s());
            assertEquals("JOB#one", raw.get("sk").s());
            assertEquals("1900000000", raw.get("expiresAt").n());
            assertFalse(aws.repo.get("alice", "JOB#one").has("pk"));
            assertNull(aws.repo.get("bob", "JOB#one"));
        }
    }
}
