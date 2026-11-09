package com.interntrack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** On-device demo data only. The cloud build continues to use API Gateway, DynamoDB and S3. */
final class DemoRepository {
    private static final Object LOCK = new Object();
    private static final long MAX_CV_BYTES = 10L * 1024 * 1024;
    private static final String[] STATUSES = {"Saved", "Applied", "Assessment", "Interview", "Offer", "Rejected", "Withdrawn"};
    private final File directory;

    DemoRepository(File directory) { this.directory = directory; }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        synchronized (LOCK) {
            JSONObject data = read();
            JSONArray applications = data.getJSONArray("applications");
            if (method.equals("GET") && path.equals("/me")) return profile();
            if (method.equals("GET") && path.equals("/dashboard"))
                return new JSONObject().put("account", profile()).put("applications", applications);
            if (method.equals("GET") && path.equals("/applications"))
                return new JSONObject().put("applications", applications);
            if (method.equals("GET") && path.equals("/cvs"))
                return new JSONObject().put("cvs", data.getJSONArray("cvs"));
            if (method.equals("POST") && path.equals("/applications")) {
                JSONObject app = application(data, UUID.randomUUID().toString(), body, null);
                applications.put(app);
                write(data);
                return app;
            }
            String[] parts = path.split("/");
            if (parts.length == 3 && parts[1].equals("applications")) {
                int index = indexOf(applications, parts[2]);
                if (index < 0) throw new Exception("Application not found");
                JSONObject previous = applications.getJSONObject(index);
                if (method.equals("GET")) return previous;
                if (method.equals("PUT")) {
                    JSONObject app = application(data, parts[2], body, previous);
                    applications.put(index, app);
                    write(data);
                    return app;
                }
                if (method.equals("DELETE")) {
                    applications.remove(index);
                    write(data);
                    return new JSONObject().put("message", "Application deleted");
                }
            }
            throw new Exception("This action requires the connected service. AI tailoring is unavailable in the offline demo.");
        }
    }

    JSONObject importCv(InputStream source, String name, long expectedSize) throws Exception {
        if (source == null) throw new Exception("Could not open the selected file");
        try (InputStream input = source) {
            synchronized (LOCK) {
                if (name == null || name.length() > 160) throw new Exception("Choose a file name of at most 160 characters");
                String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (!Arrays.asList("pdf", "docx", "txt").contains(extension))
                    throw new Exception("Please select a PDF, DOCX or TXT file");
                JSONObject data = read();
                String id = UUID.randomUUID().toString();
                File destination = new File(directory, id + "." + extension);
                boolean saved = false;
                try {
                    long size = 0;
                    try (OutputStream output = new FileOutputStream(destination)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            size += count;
                            if (size > MAX_CV_BYTES) throw new Exception("File must be at most 10 MB");
                            output.write(buffer, 0, count);
                        }
                    }
                    if (size == 0 || (expectedSize >= 0 && expectedSize != size))
                        throw new Exception("File is empty or changed while reading");
                    String mime = extension.equals("pdf") ? "application/pdf" : extension.equals("txt") ? "text/plain"
                            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    JSONObject cv = new JSONObject().put("id", id).put("name", name).put("kind", "original")
                            .put("fileName", destination.getName()).put("mimeType", mime).put("size", size)
                            .put("createdAt", Instant.now().toString());
                    data.getJSONArray("cvs").put(cv);
                    write(data);
                    saved = true;
                    return cv;
                } finally {
                    if (!saved) Files.deleteIfExists(destination.toPath());
                }
            }
        }
    }

    JSONObject cv(String id) throws Exception {
        synchronized (LOCK) {
            JSONArray cvs = read().getJSONArray("cvs");
            int index = indexOf(cvs, id);
            if (index < 0) throw new Exception("CV not found");
            return cvs.getJSONObject(index);
        }
    }

    void exportCv(String id, OutputStream destination) throws Exception {
        if (destination == null) throw new Exception("Could not open the destination");
        try (OutputStream output = destination) {
            synchronized (LOCK) {
                File file = new File(directory, cv(id).getString("fileName"));
                try (InputStream input = Files.newInputStream(file.toPath())) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
        }
    }

    private JSONObject profile() throws Exception {
        return new JSONObject().put("userId", "local-demo").put("name", "Demo student").put("email", "");
    }

    private JSONObject read() throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Could not create demo storage");
        File file = new File(directory, "records.json");
        if (file.exists()) return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        JSONObject sample = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("company", "Example Company").put("role", "Software Engineering Intern")
                .put("jobUrl", "https://example.com").put("status", "Saved").put("appliedDate", "")
                .put("notes", "Sample application. Tap to edit it, or delete it and add your own.")
                .put("jobDescription", "").put("cvId", "").put("cvName", "")
                .put("createdAt", Instant.now().toString()).put("updatedAt", Instant.now().toString());
        JSONObject data = new JSONObject().put("applications", new JSONArray().put(sample)).put("cvs", new JSONArray());
        write(data);
        return data;
    }

    private void write(JSONObject data) throws Exception {
        File temporary = new File(directory, "records.tmp");
        Files.write(temporary.toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(directory, "records.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private JSONObject application(JSONObject data, String id, JSONObject input, JSONObject previous) throws Exception {
        if (input == null) throw new Exception("Application details are required");
        JSONObject app = new JSONObject().put("id", id);
        app.put("company", text(input, "company", 120, true));
        app.put("role", text(input, "role", 160, true));
        String url = text(input, "jobUrl", 2048, false);
        if (!url.isEmpty()) {
            URI uri = new URI(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new Exception("Use a valid http or https job URL");
        }
        app.put("jobUrl", url);
        String status = input.optString("status", "Saved");
        if (!Arrays.asList(STATUSES).contains(status)) throw new Exception("Choose a valid application status");
        app.put("status", status);
        String date = text(input, "appliedDate", 10, false);
        if (!date.isEmpty()) {
            try { LocalDate.parse(date); }
            catch (Exception ex) { throw new Exception("Use a valid date in YYYY-MM-DD format"); }
        }
        app.put("appliedDate", date);
        app.put("notes", text(input, "notes", 5000, false));
        app.put("jobDescription", text(input, "jobDescription", 15000, false));
        String cvId = input.optString("cvId", "");
        int cvIndex = indexOf(data.getJSONArray("cvs"), cvId);
        if (!cvId.isEmpty() && cvIndex < 0) throw new Exception("Select a CV from your library");
        app.put("cvId", cvId).put("cvName", cvIndex < 0 ? "" : data.getJSONArray("cvs").getJSONObject(cvIndex).getString("name"));
        app.put("createdAt", previous == null ? Instant.now().toString() : previous.getString("createdAt"));
        app.put("updatedAt", Instant.now().toString());
        return app;
    }

    private static String text(JSONObject input, String field, int max, boolean required) throws Exception {
        String value = input.optString(field, "").trim();
        if ((required && value.isEmpty()) || value.length() > max) throw new Exception("Check " + field + " (maximum " + max + " characters)");
        return value;
    }

    private static int indexOf(JSONArray records, String id) throws Exception {
        for (int i = 0; i < records.length(); i++) if (id.equals(records.getJSONObject(i).optString("id"))) return i;
        return -1;
    }
}
