package com.azhardev.tenderengine;

import com.azhardev.tenderengine.entity.ProcessingStatus;
import com.azhardev.tenderengine.entity.TenderDocumentRecord;
import com.azhardev.tenderengine.exception.ConcurrentProcessingException;
import com.azhardev.tenderengine.repository.TenderDocumentRepository;
import com.azhardev.tenderengine.service.DatabaseCacheLockService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TenderEngineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenderDocumentRepository repository;

    @Autowired
    private DatabaseCacheLockService databaseCacheLockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private byte[] generateRealMultiPagePdf(String tenderRef, String authority, String amountStr) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page1 = new PDPage();
            document.addPage(page1);
            try (PDPageContentStream stream = new PDPageContentStream(document, page1)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                stream.newLineAtOffset(50, 720);
                stream.showText("Tender Notice: " + tenderRef);
                stream.newLineAtOffset(0, -25);

                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.showText("Issuing Authority: " + authority);
                stream.newLineAtOffset(0, -25);
                stream.showText("Estimated Cost: INR " + amountStr);
                stream.newLineAtOffset(0, -25);
                stream.showText("EMD Amount: INR 500000.00");
                stream.newLineAtOffset(0, -25);
                stream.showText("Submission Date: 2026-11-30");
                stream.endText();
            }

            PDPage page2 = new PDPage();
            document.addPage(page2);
            try (PDPageContentStream stream = new PDPageContentStream(document, page2)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 720);
                stream.showText("Section II: Detailed Terms, Technical Specifications and Eligibility Criteria");
                stream.newLineAtOffset(0, -20);
                stream.showText("The bidder must meet all statutory certifications and financial benchmarks.");
                stream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("POST /api/v1/tenders/ingest parses real PDF, extracts metadata, and returns 200 OK")
    void testIngestRealPdfSuccess() throws Exception {
        byte[] pdfBytes = generateRealMultiPagePdf("NIT-2026-PWD-9001", "Municipal Corporation of Delhi", "35000000.00");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "delhi_tender_9001.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                pdfBytes
        );

        String responseJson = mockMvc.perform(multipart("/api/v1/tenders/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId", notNullValue()))
                .andExpect(jsonPath("$.documentHash", notNullValue()))
                .andExpect(jsonPath("$.pageCount", is(2)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.cacheHit", is(false)))
                .andExpect(jsonPath("$.metadata.tenderReferenceNumber", notNullValue()))
                .andExpect(jsonPath("$.metadata.issuingAuthority", notNullValue()))
                .andExpect(jsonPath("$.metadata.estimatedContractValue", is(35000000.00)))
                .andExpect(jsonPath("$.metadata.emdAmount", notNullValue()))
                .andExpect(jsonPath("$.metadata.mandatoryEligibilityCriteria", hasSize(4)))
                .andExpect(jsonPath("$.metadata.complianceChecklist", hasSize(5)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseJson).contains("delhi_tender_9001.pdf");
    }

    @Test
    @DisplayName("Subsequent ingestion of identical PDF returns 200 OK with cacheHit=true")
    void testIngestIdempotentCacheHit() throws Exception {
        byte[] pdfBytes = generateRealMultiPagePdf("NIT-2026-HW-4402", "National Highways Authority", "150000000.00");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "highways_tender.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                pdfBytes
        );

        // First ingest (cache miss)
        mockMvc.perform(multipart("/api/v1/tenders/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit", is(false)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // Second ingest (cache hit)
        mockMvc.perform(multipart("/api/v1/tenders/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit", is(true)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("GET /api/v1/tenders/{documentHash} retrieves completed record or 404")
    void testGetTenderByHash() throws Exception {
        byte[] pdfBytes = generateRealMultiPagePdf("NIT-2026-METRO-110", "Delhi Metro Rail Corporation", "85000000.00");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "metro_tender.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                pdfBytes
        );

        String responseBody = mockMvc.perform(multipart("/api/v1/tenders/ingest").file(file))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract hash
        String docHash = responseBody.replaceAll(".*\"documentHash\":\"([a-f0-9]+)\".*", "$1");

        // Fetch existing
        mockMvc.perform(get("/api/v1/tenders/" + docHash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentHash", is(docHash)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // Fetch non-existent hash
        mockMvc.perform(get("/api/v1/tenders/nonexistenthash1234567890abcdef"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Active LOCKED_PROCESSING lock throws ConcurrentProcessingException and returns 409 Conflict")
    void testActiveLockReturns409Conflict() throws Exception {
        String testHash = "active_locked_hash_test_1234567890abcdef1234567890abcdef12345678";
        TenderDocumentRecord activeLock = TenderDocumentRecord.builder()
                .documentHash(testHash)
                .fileName("active_lock.pdf")
                .pageCount(2)
                .status(ProcessingStatus.LOCKED_PROCESSING)
                .build();
        repository.saveAndFlush(activeLock);

        // Attempting to acquire lock on an active record should throw ConcurrentProcessingException
        assertThatThrownBy(() -> databaseCacheLockService.acquireLockOrThrow(testHash, "new_upload.pdf", 2))
                .isInstanceOf(ConcurrentProcessingException.class)
                .hasMessageContaining("actively being processed");
    }

    @Test
    @DisplayName("Stale LOCKED_PROCESSING lock is successfully recovered after timeout")
    void testStaleLockRecovery() {
        String staleHash = "stale_locked_hash_test_1234567890abcdef1234567890abcdef12345678";
        TenderDocumentRecord staleRecord = TenderDocumentRecord.builder()
                .documentHash(staleHash)
                .fileName("crashed_worker.pdf")
                .pageCount(1)
                .status(ProcessingStatus.LOCKED_PROCESSING)
                .build();
        staleRecord = repository.saveAndFlush(staleRecord);

        // Simulate stale lock older than 5 seconds (configured in test application.yml) via direct SQL to bypass Hibernate @UpdateTimestamp
        jdbcTemplate.update(
                "UPDATE tender_document_records SET updated_at = ? WHERE document_hash = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.SECONDS)),
                staleHash
        );

        // Call acquireLockOrThrow, should recover without throwing exception
        TenderDocumentRecord recovered = databaseCacheLockService.acquireLockOrThrow(
                staleHash, "recovered_document.pdf", 3
        );

        assertThat(recovered).isNotNull();
        assertThat(recovered.getStatus()).isEqualTo(ProcessingStatus.LOCKED_PROCESSING);
        assertThat(recovered.getFileName()).isEqualTo("recovered_document.pdf");
        assertThat(recovered.getPageCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("POST /api/v1/tenders/ingest with empty file returns 400 Bad Request")
    void testEmptyFileUploadReturnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/tenders/ingest").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("GET /api/v1/tenders/search returns completed tender records matching issuing authority")
    void testSearchByIssuingAuthority() throws Exception {
        byte[] pdfBytes = generateRealMultiPagePdf("NIT-2026-SEARCH-01", "National Highways Authority", "99000000.00");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "search_tender.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                pdfBytes
        );

        mockMvc.perform(multipart("/api/v1/tenders/ingest").file(file))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tenders/search").param("authority", "Highways"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileName", is("search_tender.pdf")));
    }
}
