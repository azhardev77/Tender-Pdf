package com.azhardev.tenderengine.dto;

public record PdfExtractionResult(
    String documentHash,
    int pageCount,
    String extractedText
) {}
