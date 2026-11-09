package app.tracker;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

public final class Validation {
    private static final Set<String> STATUSES = Set.of("Saved", "Applied", "Assessment", "Interview", "Offer", "Rejected", "Withdrawn");
    private Validation() { }

    public static String required(String text, String name, int max) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.length() > max) throw new BadRequest(name + " must contain 1-" + max + " characters");
        return value;
    }
    public static String optional(String text, String name, int max) {
        String value = text == null ? "" : text.strip();
        if (value.length() > max) throw new BadRequest(name + " exceeds " + max + " characters");
        return value;
    }
    public static String status(String text) {
        if (!STATUSES.contains(text)) throw new BadRequest("Invalid status");
        return text;
    }
    public static String date(String text) {
        if (text == null || text.isBlank()) return "";
        try { return LocalDate.parse(text).toString(); }
        catch (DateTimeParseException ex) { throw new BadRequest("Date must use YYYY-MM-DD"); }
    }
    public static String url(String text) {
        String value = optional(text, "Job URL", 2048);
        if (value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new Exception();
        } catch (Exception ex) { throw new BadRequest("Job URL must be a valid https:// URL"); }
        return value;
    }
    public static String uuid(String text) {
        try { return UUID.fromString(text).toString(); }
        catch (Exception ex) { throw new BadRequest("Invalid record ID"); }
    }
    public static String mime(String filename, String claimed) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".txt")) return "text/plain";
        throw new BadRequest("Upload a PDF, DOCX or TXT CV");
    }
    public static String extension(String mime) {
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "text/plain" -> "txt";
            default -> throw new BadRequest("Unsupported CV format");
        };
    }
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) { super(message); }
    }
}
