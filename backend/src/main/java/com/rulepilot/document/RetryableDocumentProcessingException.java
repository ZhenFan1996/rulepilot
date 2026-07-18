package com.rulepilot.document;

public class RetryableDocumentProcessingException extends RuntimeException {

    public RetryableDocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
