package com.azhardev.tenderengine.dto;

import com.azhardev.tenderengine.entity.ProcessingStatus;

import java.time.Instant;
import java.util.UUID;

public record TenderExtractionResponse(
    UUID recordId,
    String documentHash,
    String fileName,
    int pageCount,
    ProcessingStatus status,
    boolean cacheHit,
    long executionTimeMs,
    TenderMetadataSchema metadata,
    Instant processedAt
) {}
