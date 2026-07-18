package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingStage;
import java.time.Instant;
import java.util.UUID;

public interface DocumentProcessingJobStore {

    void update(UUID jobId, DocumentProcessingStage stage, String status, Instant updatedAt);
}
