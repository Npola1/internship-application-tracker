package com.interntrack;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class DemoRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private DemoRepository repository;

    @Before public void setUp() throws Exception {
        directory = new File(temporary.getRoot(), "demo");
        repository = new DemoRepository(directory);
    }

    private JSONObject application() throws Exception {
        return new JSONObject().put("company", "Student Test Co").put("role", "Engineering Intern")
                .put("status", "Saved").put("jobUrl", "https://example.org/jobs/1");
    }

    @Test public void applicationChangesSurviveFreshRepositoryInstances() throws Exception {
        JSONObject created = repository.request("POST", "/applications", application());
        String path = "/applications/" + created.getString("id");
        DemoRepository reopened = new DemoRepository(directory);
        JSONObject saved = reopened.request("GET", path, null);
        assertEquals("Student Test Co", saved.getString("company"));
        saved.put("status", "Interview").put("notes", "Call next week");
        reopened.request("PUT", path, saved);
        JSONObject updated = new DemoRepository(directory).request("GET", path, null);
        assertEquals("Interview", updated.getString("status"));
        assertEquals("Call next week", updated.getString("notes"));
        assertEquals(created.getString("createdAt"), updated.getString("createdAt"));
        reopened.request("DELETE", path, null);
        assertThrows(Exception.class, () -> new DemoRepository(directory).request("GET", path, null));
    }

    @Test public void importedCvCanBeReopenedExportedAndAttached() throws Exception {
        byte[] bytes = "Student CV\nSkills: Java\nProject: application tracker".getBytes(StandardCharsets.UTF_8);
        JSONObject cv = repository.importCv(new ByteArrayInputStream(bytes), "my-cv.txt", bytes.length);
        String id = cv.getString("id");
        DemoRepository reopened = new DemoRepository(directory);
        assertEquals("my-cv.txt", reopened.cv(id).getString("name"));
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        reopened.exportCv(id, copy);
        assertArrayEquals(bytes, copy.toByteArray());
        JSONObject created = reopened.request("POST", "/applications", application().put("cvId", id).put("cvName", "wrong.txt"));
        assertEquals(id, created.getString("cvId"));
        assertEquals("my-cv.txt", created.getString("cvName"));
        JSONObject dashboard = new DemoRepository(directory).request("GET", "/dashboard", null);
        assertEquals("local-demo", dashboard.getJSONObject("account").getString("userId"));
        assertEquals(1, reopened.request("GET", "/cvs", null).getJSONArray("cvs").length());
        reopened.request("DELETE", "/applications/" + created.getString("id"), null);
        assertEquals("my-cv.txt", reopened.cv(id).getString("name"));
    }

    @Test public void rejectsInvalidApplicationValuesWithoutSavingThem() throws Exception {
        int before = repository.request("GET", "/applications", null).getJSONArray("applications").length();
        assertThrows(Exception.class, () -> repository.request("POST", "/applications", application().put("jobUrl", "javascript:alert(1)")));
        assertThrows(Exception.class, () -> repository.request("POST", "/applications", application().put("appliedDate", "2026-02-30")));
        assertThrows(Exception.class, () -> repository.request("POST", "/applications", application().put("status", "invalid")));
        assertThrows(Exception.class, () -> repository.request("POST", "/applications", application().put("cvId", "missing")));
        assertEquals(before, repository.request("GET", "/applications", null).getJSONArray("applications").length());
    }

    @Test public void incompleteAndOversizeCvImportsLeaveNoFilesOrMetadata() throws Exception {
        InputStream oversized = new InputStream() {
            private long remaining = 10L * 1024 * 1024 + 1;
            @Override public int read() { if (remaining-- <= 0) return -1; return 'x'; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                remaining -= count;
                return count;
            }
        };
        assertThrows(Exception.class, () -> repository.importCv(oversized, "too-large.pdf", -1));
        assertThrows(Exception.class, () -> repository.importCv(new ByteArrayInputStream(new byte[0]), "empty.txt", 0));
        assertThrows(Exception.class, () -> repository.importCv(new ByteArrayInputStream(new byte[]{1}), "changed.txt", 3));
        assertThrows(Exception.class, () -> repository.importCv(new ByteArrayInputStream(new byte[]{1}), "app.exe", 1));
        assertEquals(0, repository.request("GET", "/cvs", null).getJSONArray("cvs").length());
        assertArrayEquals(new String[]{"records.json"}, directory.list());
    }

    @Test public void simultaneousActivityWritesAreRetained() throws Exception {
        int before = repository.request("GET", "/applications", null).getJSONArray("applications").length();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try { new DemoRepository(directory).request("POST", "/applications", application().put("company", "First")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            Future<?> second = executor.submit(() -> {
                try { new DemoRepository(directory).request("POST", "/applications", application().put("company", "Second")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            first.get();
            second.get();
        } finally { executor.shutdownNow(); }
        assertEquals(before + 2, new DemoRepository(directory).request("GET", "/applications", null).getJSONArray("applications").length());
    }

    @Test public void deletedExampleDoesNotReappearAfterRestart() throws Exception {
        JSONArray applications = repository.request("GET", "/applications", null).getJSONArray("applications");
        repository.request("DELETE", "/applications/" + applications.getJSONObject(0).getString("id"), null);
        assertEquals(0, new DemoRepository(directory).request("GET", "/applications", null).getJSONArray("applications").length());
    }

    @Test public void tailoringDoesNotPretendToGenerateAnOfflineAiResult() throws Exception {
        Exception error = assertThrows(Exception.class, () -> repository.request("POST", "/applications/example/tailor", new JSONObject()));
        assertTrue(error.getMessage().contains("AI tailoring is unavailable"));
        assertEquals(0, repository.request("GET", "/cvs", null).getJSONArray("cvs").length());
    }
}
