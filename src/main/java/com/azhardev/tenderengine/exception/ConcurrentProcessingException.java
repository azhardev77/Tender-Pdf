package com.azhardev.tenderengine.exception;

public class ConcurrentProcessingException extends RuntimeException {

    public ConcurrentProcessingException(String message) {
        super(message);
    }

    public ConcurrentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
