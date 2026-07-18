package com.rulepilot.document;

import java.util.UUID;

public interface DocumentProcessingIdempotency {

    boolean begin(DocumentProcessingCommand command, UUID eventId, int attempt);

    void complete(DocumentProcessingCommand command);

    void fail(DocumentProcessingCommand command, String errorCode);
}
