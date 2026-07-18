package com.rulepilot.document;

import java.util.UUID;

public interface DocumentProcessingIdempotency {

    boolean begin(DocumentProcessingCommand command, UUID eventId);

    void complete(DocumentProcessingCommand command);

    void fail(DocumentProcessingCommand command);
}
