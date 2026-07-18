package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingCommand;
import java.time.Instant;
import java.util.UUID;

public interface DocumentProcessingDeduplicationStore {

    boolean begin(DocumentProcessingCommand command, UUID eventId, Instant startedAt);

    void update(DocumentProcessingCommand command, String status, Instant completedAt);
}
