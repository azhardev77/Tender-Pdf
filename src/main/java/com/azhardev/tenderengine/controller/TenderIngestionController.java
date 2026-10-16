package com.azhardev.tenderengine.controller;

import com.azhardev.tenderengine.dto.TenderExtractionResponse;
import com.azhardev.tenderengine.entity.TenderDocumentRecord;
import com.azhardev.tenderengine.exception.DocumentProcessingException;
import com.azhardev.tenderengine.repository.TenderDocumentRepository;
import com.azhardev.tenderengine.service.TenderOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenders")
public class TenderIngestionController {

    private static final Logger log = LoggerFactory.getLogger(TenderIngestionController.class);

    private final TenderOrchestratorService orchestratorService;
    private final TenderDocumentRepository documentRepository;

    public TenderIngestionController(TenderOrchestratorService orchestratorService,
                                     TenderDocumentRepository documentRepository) {
        this.orchestratorService = orchestratorService;
        this.documentRepository = documentRepository;
    }

    /**
     * Ingests a tender PDF document, extracts metadata, and persists the record.
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TenderExtractionResponse> ingestTenderDocument(
            @RequestParam("file") MultipartFile file) {
        log.info("Received tender document ingestion request: name={}, size={} bytes",
                file != null ? file.getOriginalFilename() : "null",
                file != null ? file.getSize() : 0);

        if (file == null || file.isEmpty()) {
            throw new DocumentProcessingException("Uploaded PDF file is empty or null");
        }

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if ((contentType != null && !contentType.equalsIgnoreCase(MediaType.APPLICATION_PDF_VALUE) && !contentType.equalsIgnoreCase("application/octet-stream"))
                && (originalFilename != null && !originalFilename.toLowerCase().endsWith(".pdf"))) {
            throw new DocumentProcessingException("Invalid file format. Only PDF files are supported.");
        }

        TenderExtractionResponse response = orchestratorService.ingestTenderDocument(file);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves an existing tender record by its SHA-256 document hash.
     */
    @GetMapping("/{documentHash}")
    public ResponseEntity<TenderExtractionResponse> getTenderByHash(
            @PathVariable("documentHash") String documentHash) {
        log.debug("Fetching tender document for hash: {}", documentHash);
        return orchestratorService.getExtractionByHash(documentHash)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Searches completed tender records filtered by issuing authority keyword.
     */
    @GetMapping("/search")
    public ResponseEntity<List<TenderDocumentRecord>> searchByIssuingAuthority(
            @RequestParam(name = "authority", required = false) String authority) {
        log.debug("Searching tender records for authority: {}", authority);
        List<TenderDocumentRecord> results = documentRepository.searchByIssuingAuthority(authority);
        return ResponseEntity.ok(results);
    }
}
