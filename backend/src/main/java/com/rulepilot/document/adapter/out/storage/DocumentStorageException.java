package com.rulepilot.document.adapter.out.storage;

import com.rulepilot.document.RetryableDocumentProcessingException;

public class DocumentStorageException extends RetryableDocumentProcessingException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
