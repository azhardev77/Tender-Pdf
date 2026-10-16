package com.azhardev.tenderengine.service;

import com.azhardev.tenderengine.dto.PdfExtractionResult;
import com.azhardev.tenderengine.exception.DocumentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class PdfStreamExtractorService {

    private static final Logger log = LoggerFactory.getLogger(PdfStreamExtractorService.class);

    private static final Pattern PAGE_NOISE_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:page\\s*\\d+\\s*(?:of|/)\\s*\\d+|\\d+\\s*(?:of|/)\\s*\\d+|\\bpage\\s*\\d+\\b)\\s*$",
            Pattern.MULTILINE
    );

    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(\\r?\\n){3,}");

    /**
     * Computes the deterministic SHA-256 fingerprint for a byte array.
     */
    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(bytes);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new DocumentProcessingException("SHA-256 algorithm not available in runtime", e);
        }
    }

    /**
     * Extracts text, page count, and SHA-256 fingerprint from a MultipartFile.
     */
    public PdfExtractionResult extract(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return extractFromBytes(bytes, computeSha256(bytes));
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read MultipartFile stream", e);
        }
    }

    /**
     * Extracts text, page count, and SHA-256 fingerprint from an InputStream.
     */
    public PdfExtractionResult extract(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            return extractFromBytes(bytes, computeSha256(bytes));
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read InputStream stream", e);
        }
    }

    /**
     * Extracts text page-by-page using PDFBox 3.0.2 streaming loader and strips noise.
     */
    public PdfExtractionResult extractFromBytes(byte[] bytes, String documentHash) {
        log.info("Starting PDF text extraction for document hash: {} ({} bytes)", documentHash, bytes.length);

        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            log.info("PDF document opened successfully. Total pages: {}", pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            StringBuilder compositeText = new StringBuilder();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(document);
                String cleanedPageText = stripHeaderFooterNoise(pageText);

                if (!cleanedPageText.isBlank()) {
                    compositeText.append("--- PAGE ").append(page).append(" ---\n");
                    compositeText.append(cleanedPageText).append("\n\n");
                }
            }

            String fullText = compositeText.toString().trim();
            log.info("Extraction finished for hash: {}. Extracted character count: {}", documentHash, fullText.length());

            return new PdfExtractionResult(documentHash, pageCount, fullText);
        } catch (IOException e) {
            log.error("Error while parsing PDF stream for hash: {}", documentHash, e);
            throw new DocumentProcessingException("Failed to extract text from PDF document: " + e.getMessage(), e);
        }
    }

    /**
     * Strips common header, footer, page number noise, and excess whitespace.
     */
    private String stripHeaderFooterNoise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = PAGE_NOISE_PATTERN.matcher(text).replaceAll("");
        cleaned = EXCESS_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }
}
