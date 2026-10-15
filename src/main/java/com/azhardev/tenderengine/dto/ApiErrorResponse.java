package com.azhardev.tenderengine.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
    int status,
    String error,
    String message,
    String path,
    Instant timestamp,
    Map<String, String> details
) {
    public ApiErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, Instant.now(), null);
    }

    public ApiErrorResponse(int status, String error, String message) {
        this(status, error, message, null, Instant.now(), null);
    }
}
