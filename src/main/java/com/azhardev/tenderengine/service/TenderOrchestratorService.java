package com.azhardev.tenderengine.service;

import com.azhardev.tenderengine.dto.PdfExtractionResult;
import com.azhardev.tenderengine.dto.TenderExtractionResponse;
import com.azhardev.tenderengine.dto.TenderMetadataSchema;
import com.azhardev.tenderengine.entity.ProcessingStatus;
import com.azhardev.tenderengine.entity.TenderDocumentRecord;
import com.azhardev.tenderengine.exception.DocumentProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenderOrchestratorService {

    private final PdfStreamExtractorService pdfStreamExtractorService;
    private final DatabaseCacheLockService databaseCacheLockService;
    private final StructuredLlmPipelineService llmPipelineService;

    /**
     * Entry point for MultipartFile uploads.
     */
    public TenderExtractionResponse processTenderPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentProcessingException("Uploaded PDF file is empty or null");
        }
        try {
            byte[] bytes = file.getBytes();
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
            return processTenderPdf(bytes, originalFilename);
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read uploaded PDF file bytes", e);
        }
    }

    /**
     * Entry point for raw InputStream processing.
     */
    public TenderExtractionResponse processTenderPdf(InputStream inputStream, String fileName) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            return processTenderPdf(bytes, fileName);
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read PDF input stream bytes", e);
        }
    }

    /**
     * Core orchestrator method executing caching, locking, parsing, and persistence.
     */
    public TenderExtractionResponse processTenderPdf(byte[] fileBytes, String fileName) {
        long startTime = System.currentTimeMillis();

        if (fileBytes == null || fileBytes.length == 0) {
            throw new DocumentProcessingException("PDF file content is empty");
        }

        // 1. Compute SHA-256 fingerprint
        String documentHash = pdfStreamExtractorService.computeSha256(fileBytes);
        log.info("Processing tender PDF '{}' with SHA-256 hash: {}", fileName, documentHash);

        // 2. Fast cache path check (<25ms sub-second response)
        Optional<TenderDocumentRecord> cachedOpt = databaseCacheLockService.checkFastCachePath(documentHash);
        if (cachedOpt.isPresent()) {
            TenderDocumentRecord cached = cachedOpt.get();
            long executionTimeMs = System.currentTimeMillis() - startTime;
            log.info("Sub-second cache hit (<{}ms) for document hash: {}", executionTimeMs, documentHash);
            return new TenderExtractionResponse(
                    cached.getId(),
                    cached.getDocumentHash(),
                    cached.getFileName(),
                    cached.getPageCount() != null ? cached.getPageCount() : 0,
                    cached.getStatus(),
                    true,
                    executionTimeMs,
                    cached.getExtractedMetadata(),
                    cached.getUpdatedAt() != null ? cached.getUpdatedAt() : Instant.now()
            );
        }

        // 3. Cache miss: extract PDF text and page count
        PdfExtractionResult extraction = pdfStreamExtractorService.extractFromBytes(fileBytes, documentHash);

        // 4. Acquire database lock (PostgreSQL advisory transaction lock + row-level write lock)
        TenderDocumentRecord lockedRecord = databaseCacheLockService.acquireLockOrThrow(
                documentHash,
                fileName,
                extraction.pageCount()
        );

        // If another worker completed it right while acquiring lock
        if (lockedRecord.getStatus() == ProcessingStatus.COMPLETED) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            log.info("Cache hit resolved post-lock (<{}ms) for document hash: {}", executionTimeMs, documentHash);
            return new TenderExtractionResponse(
                    lockedRecord.getId(),
                    lockedRecord.getDocumentHash(),
                    lockedRecord.getFileName(),
                    lockedRecord.getPageCount() != null ? lockedRecord.getPageCount() : extraction.pageCount(),
                    lockedRecord.getStatus(),
                    true,
                    executionTimeMs,
                    lockedRecord.getExtractedMetadata(),
                    lockedRecord.getUpdatedAt() != null ? lockedRecord.getUpdatedAt() : Instant.now()
            );
        }

        // 5. Execute LLM structured extraction pipeline with fallback
        try {
            log.info("Executing LLM / Heuristic pipeline for document hash: {}", documentHash);
            TenderMetadataSchema metadata = llmPipelineService.extractMetadataFromText(
                    extraction.extractedText(),
                    fileName
            );

            long executionTimeMs = System.currentTimeMillis() - startTime;

            // 6. Complete record with extracted JSONB metadata
            TenderDocumentRecord completedRecord = databaseCacheLockService.completeRecord(
                    lockedRecord.getId(),
                    metadata,
                    executionTimeMs
            );

            log.info("Tender ingestion completed successfully in {}ms for hash: {}", executionTimeMs, documentHash);
            return new TenderExtractionResponse(
                    completedRecord.getId(),
                    completedRecord.getDocumentHash(),
                    completedRecord.getFileName(),
                    completedRecord.getPageCount(),
                    completedRecord.getStatus(),
                    false,
                    executionTimeMs,
                    completedRecord.getExtractedMetadata(),
                    Instant.now()
            );
        } catch (Exception ex) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            log.error("Pipeline failure for document hash: {}. Marking record as FAILED.", documentHash, ex);
            databaseCacheLockService.failRecord(lockedRecord.getId(), executionTimeMs);
            if (ex instanceof DocumentProcessingException dpe) {
                throw dpe;
            }
            throw new DocumentProcessingException("Error during tender extraction pipeline: " + ex.getMessage(), ex);
        }
    }
}
