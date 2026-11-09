package app.tracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CvServiceTest {
    private TestAws aws;
    private static final String TEXT="STUDENT CV\nJava, Git and unit testing. Built an application tracker.";
    @BeforeEach void setup() { aws=new TestAws(); }
    @AfterEach void close() { aws.close(); }
    @ParameterizedTest @ValueSource(strings={"Student.txt","Student.PDF","Student.docx"})
    void allowlistedFormatsUploadCompleteListAndDownloadPrivately(String name) {
        var cv=aws.upload("alice", name, TEXT.getBytes(StandardCharsets.UTF_8));
        assertTrue(cv.path("objectKey").asText().startsWith("users/alice/cvs/"));
        assertEquals(1, aws.repo.list("alice", "CV#").size());
        assertEquals(0, aws.repo.list("alice", "UPLOAD#").size());
        var download=aws.cvs.downloadUrl("alice", cv.path("id").asText());
        URI url=URI.create(download.path("url").asText());
        assertEquals("https", url.getScheme());
        assertTrue(url.getQuery().contains("X-Amz-Expires=600"));
        assertEquals(name, download.path("name").asText());
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), aws.cvs.bytes("alice", cv.path("id").asText()));
        assertThrows(Handler.NotFound.class, () -> aws.cvs.get("bob", cv.path("id").asText()));
        assertThrows(Handler.NotFound.class, () -> aws.cvs.completeUpload("alice", cv.path("id").asText()));
    }
    @Test void uploadUrlExpiresAndPendingMetadataDoesNotAppearInTheLibrary() {
        var init=aws.cvs.prepareUpload("alice", "Original.txt", 10);
        assertTrue(init.path("uploadUrl").asText().contains("X-Amz-Expires=900"));
        assertEquals(0, aws.repo.list("alice", "CV#").size());
        assertEquals(1, aws.repo.list("alice", "UPLOAD#").size());
        assertThrows(Handler.NotFound.class, () -> aws.cvs.completeUpload("bob", init.path("id").asText()));
    }
    @Test void rejectsUnsupportedEmptyOversizedAndOverlongNamesBeforeCreatingRecords() {
        for (long size : new long[]{-1,0,CvService.MAX_BYTES+1})
            assertThrows(Validation.BadRequest.class, () -> aws.cvs.prepareUpload("alice", "CV.pdf", size));
        for (String name : new String[]{"CV.exe", " ", "x".repeat(161)+".txt"})
            assertThrows(Validation.BadRequest.class, () -> aws.cvs.prepareUpload("alice", name, 1));
        assertTrue(aws.records.isEmpty());
        assertNotNull(aws.cvs.prepareUpload("alice", "Limit.txt", CvService.MAX_BYTES));
    }
    @Test void rejectsMissingExpiredSizeMismatchedAndMimeMismatchedUploadsWithoutCreatingCv() {
        var init=aws.cvs.prepareUpload("alice", "CV.txt", 3);
        String id=init.path("id").asText();
        var pending=aws.repo.get("alice", "UPLOAD#"+id);
        String key=pending.path("objectKey").asText();
        assertThrows(Validation.BadRequest.class, () -> aws.cvs.completeUpload("alice", id));
        aws.files.put(key,new byte[]{1,2}); aws.mime.put(key,"text/plain");
        assertThrows(Validation.BadRequest.class, () -> aws.cvs.completeUpload("alice", id));
        aws.files.put(key,new byte[]{1,2,3}); aws.mime.put(key,"application/pdf");
        assertThrows(Validation.BadRequest.class, () -> aws.cvs.completeUpload("alice", id));
        pending.put("expiresAt",Instant.now().minusSeconds(1).getEpochSecond());
        aws.repo.put("alice","UPLOAD#"+id,pending);
        assertThrows(Validation.BadRequest.class, () -> aws.cvs.completeUpload("alice", id));
        assertEquals(0,aws.repo.list("alice","CV#").size());
    }
    @Test void extractsActualTxtPdfAndDocxDocumentsIncludingDocxTables() throws Exception {
        assertEquals(TEXT,aws.cvs.text(TEXT.getBytes(StandardCharsets.UTF_8),"text/plain"));
        byte[] pdf;
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage(); document.addPage(page);
            try(var content=new PDPageContentStream(document,page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(30,700); content.showText(TEXT.replace('\n',' ')); content.endText();
            }
            document.save(bytes); pdf=bytes.toByteArray();
        }
        assertTrue(aws.cvs.text(pdf,"application/pdf").contains("Java, Git and unit testing"));
        byte[] docx;
        try(var document=new XWPFDocument();var bytes=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(TEXT);
            document.createTable(1,1).getRow(0).getCell(0).setText("Table skill: Python");
            document.write(bytes); docx=bytes.toByteArray();
        }
        assertTrue(aws.cvs.text(docx,"application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains("Table skill: Python"));
    }
    @Test void scannedCorruptUnsupportedAndTooShortDocumentsFailClearly() throws Exception {
        byte[] blankPdf;
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(bytes); blankPdf=bytes.toByteArray();
        }
        assertTrue(assertThrows(Validation.BadRequest.class,()->aws.cvs.text(blankPdf,"application/pdf")).getMessage().contains("OCR"));
        assertThrows(IOException.class,()->aws.cvs.text(new byte[]{1,2,3},"application/pdf"));
        assertThrows(Validation.BadRequest.class,()->aws.cvs.text(TEXT.getBytes(StandardCharsets.UTF_8),"image/png"));
        assertThrows(Validation.BadRequest.class,()->aws.cvs.text("short".getBytes(StandardCharsets.UTF_8),"text/plain"));
        assertEquals(25000,aws.cvs.text("x".repeat(26000).getBytes(StandardCharsets.UTF_8),"text/plain").length());
    }
    @Test void generatedDocxCanBeRetrievedWithSourceAndApplicationReferences() throws Exception {
        String app="11111111-1111-4111-8111-111111111111";
        var cv=aws.cvs.storeTailored("alice",app,"source-cv",TEXT);
        assertEquals(app,cv.path("applicationId").asText());
        assertEquals("source-cv",cv.path("sourceCvId").asText());
        assertEquals("tailored",cv.path("kind").asText());
        byte[] bytes=aws.cvs.bytes("alice",cv.path("id").asText());
        try(var document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p->p.getText().contains("Java, Git")));
        }
        assertEquals(bytes.length,cv.path("size").asInt());
    }
}
