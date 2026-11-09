package app.tracker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    @Test void validatesStatuses() {
        assertEquals("Applied", Validation.status("Applied"));
        assertThrows(Validation.BadRequest.class, () -> Validation.status("Hired"));
    }
    @Test void rejectsBadUrls() {
        assertEquals("https://jobs.example.com/1", Validation.url("https://jobs.example.com/1"));
        assertThrows(Validation.BadRequest.class, () -> Validation.url("javascript:alert(1)"));
    }
    @Test void requiresValidDate() {
        assertEquals("2027-06-01", Validation.date("2027-06-01"));
        assertThrows(Validation.BadRequest.class, () -> Validation.date("01/06/27"));
    }
    @Test void enforcesCvFormat() {
        assertEquals("docx", Validation.extension(Validation.mime("cv.DOCX", "application/octet-stream")));
        assertThrows(Validation.BadRequest.class, () -> Validation.mime("payload.exe", "application/pdf"));
    }
}
