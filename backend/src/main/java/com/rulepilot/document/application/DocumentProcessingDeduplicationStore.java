package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DocumentProcessingDeduplicationStore {

    boolean begin(DocumentProcessingCommand command, UUID eventId, int attempt, Instant startedAt);

    void update(DocumentProcessingCommand command, String status, String errorCode, Instant completedAt);

    Optional<DocumentProcessingCommand> findFailed(UUID jobId);

    void resetJob(UUID jobId, String stage, Instant updatedAt);
}
