package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class CvService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private final TrackerRepository repository;
    private final ObjectMapper json;
    private final S3Client s3;
    private final S3Presigner signer;
    private final String bucket;

    public CvService(TrackerRepository repository, ObjectMapper json, S3Client s3, S3Presigner signer, String bucket) {
        this.repository = repository;
        this.json = json;
        this.s3 = s3;
        this.signer = signer;
        this.bucket = bucket;
    }
    private static ObjectNode mustFind(TrackerRepository repository, String user, String id) {
        ObjectNode item = repository.get(user, "CV#" + Validation.uuid(id));
        if (item == null) throw new Handler.NotFound("CV not found");
        return item;
    }
    public ObjectNode prepareUpload(String user, String filename, long size) {
        filename = Validation.required(filename, "File name", 160);
        String mime = Validation.mime(filename, "");
        if (size < 1 || size > MAX_BYTES) throw new Validation.BadRequest("CV must be between 1 byte and 10 MB");
        String id = UUID.randomUUID().toString();
        String path = "users/" + user + "/cvs/" + id + "/source." + Validation.extension(mime);
        ObjectNode pending = json.createObjectNode();
        pending.put("id", id);
        pending.put("name", filename);
        pending.put("mimeType", mime);
        pending.put("size", Long.toString(size));
        pending.put("objectKey", path);
        pending.put("expiresAt", Long.toString(Instant.now().plus(Duration.ofMinutes(20)).getEpochSecond()));
        repository.put(user, "UPLOAD#" + id, pending);
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(path).contentType(mime).build();
        String uploadUrl = signer.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15)).putObjectRequest(put).build()).url().toExternalForm();
        ObjectNode output = json.createObjectNode();
        output.put("id", id);
        output.put("uploadUrl", uploadUrl);
        output.put("contentType", mime);
        output.put("expiresInSeconds", 900);
        return output;
    }
    public ObjectNode completeUpload(String user, String id) {
        id = Validation.uuid(id);
        ObjectNode pending = repository.get(user, "UPLOAD#" + id);
        if (pending == null) throw new Handler.NotFound("Pending upload not found or already completed");
        if (Instant.now().getEpochSecond() > pending.path("expiresAt").asLong())
            throw new Validation.BadRequest("Upload session expired. Start a new upload.");
        String path = pending.path("objectKey").asText();
        HeadObjectResponse head;
        try { head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(path).build()); }
        catch (S3Exception ex) { if (ex.statusCode() == 404) throw new Validation.BadRequest("Upload not found in S3"); else throw ex; }
        if (head.contentLength() != pending.path("size").asLong() || head.contentLength() > MAX_BYTES)
            throw new Validation.BadRequest("Uploaded file size does not match");
        if (!pending.path("mimeType").asText().equals(head.contentType()))
            throw new Validation.BadRequest("Uploaded file Content-Type does not match");
        ObjectNode cv = json.createObjectNode();
        cv.put("id", id);
        cv.put("name", pending.path("name").asText());
        cv.put("mimeType", pending.path("mimeType").asText());
        cv.put("size", pending.path("size").asText());
        cv.put("objectKey", path);
        cv.put("kind", "original");
        cv.put("createdAt", TrackerRepository.timestamp());
        repository.put(user, "CV#" + id, cv);
        repository.remove(user, "UPLOAD#" + id);
        return cv;
    }
    public ObjectNode get(String user, String id) { return mustFind(repository, user, id); }
    public byte[] bytes(String user, String id) {
        ObjectNode cv = get(user, id);
        ResponseBytes<GetObjectResponse> value = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                .key(cv.path("objectKey").asText()).build());
        if (value.asByteArray().length > MAX_BYTES) throw new Validation.BadRequest("CV exceeds processing size limit");
        return value.asByteArray();
    }
    public ObjectNode downloadUrl(String user, String id) {
        ObjectNode cv = get(user, id);
        String url = signer.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket)
                    .key(cv.path("objectKey").asText()).build()).build()).url().toExternalForm();
        ObjectNode result = json.createObjectNode();
        result.put("url", url);
        result.put("mimeType", cv.path("mimeType").asText());
        result.put("name", cv.path("name").asText());
        return result;
    }
    public String text(byte[] bytes, String mime) throws IOException {
        String extracted;
        switch (mime) {
            case "text/plain" -> extracted = new String(bytes, StandardCharsets.UTF_8);
            case "application/pdf" -> {
                try (var pdf = Loader.loadPDF(bytes)) { extracted = new PDFTextStripper().getText(pdf); }
            }
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    StringBuilder out = new StringBuilder();
                    for (XWPFParagraph p : doc.getParagraphs()) out.append(p.getText()).append('\n');
                    for (var table : doc.getTables()) for (var row : table.getRows())
                        for (var cell : row.getTableCells()) out.append(cell.getText()).append('\n');
                    extracted = out.toString();
                }
            }
            default -> throw new Validation.BadRequest("Unsupported CV format");
        }
        extracted = extracted.strip();
        if (extracted.length() < 30) throw new Validation.BadRequest("No usable text in CV. Scanned PDFs need OCR, so upload a text-based PDF, DOCX or TXT.");
        return extracted.substring(0, Math.min(extracted.length(), 25000));
    }
    public ObjectNode storeTailored(String user, String appId, String sourceId, String output) throws IOException {
        String id = UUID.randomUUID().toString();
        String path = "users/" + user + "/cvs/" + id + "/tailored.docx";
        byte[] bytes = toDocx(output);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(path)
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .build(), RequestBody.fromBytes(bytes));
        ObjectNode cv = json.createObjectNode();
        cv.put("id", id);
        cv.put("name", "Tailored-CV-" + appId.substring(0, 8) + ".docx");
        cv.put("mimeType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        cv.put("size", Integer.toString(bytes.length));
        cv.put("objectKey", path);
        cv.put("kind", "tailored");
        cv.put("sourceCvId", sourceId);
        cv.put("applicationId", appId);
        cv.put("createdAt", TrackerRepository.timestamp());
        repository.put(user, "CV#" + id, cv);
        return cv;
    }
    public static byte[] toDocx(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            for (String line : text.split("\\R")) {
                String cleaned = line.strip();
                if (cleaned.isEmpty()) continue;
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setSpacingAfter(110);
                XWPFRun run = paragraph.createRun();
                boolean heading = cleaned.length() < 52 && cleaned.equals(cleaned.toUpperCase(java.util.Locale.ROOT)) && cleaned.matches(".*[A-Z].*");
                if (heading) { run.setBold(true); run.setFontSize(13); }
                else { run.setFontSize(10); }
                run.setFontFamily("Arial");
                run.setText(cleaned);
            }
            document.write(buffer);
            return buffer.toByteArray();
        }
    }
}
