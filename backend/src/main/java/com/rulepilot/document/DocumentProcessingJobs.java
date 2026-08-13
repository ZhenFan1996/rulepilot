package com.rulepilot.document;

import java.time.Instant;
import java.util.UUID;

public interface DocumentProcessingJobs {

    void stageStarted(UUID jobId, DocumentProcessingStage stage);

    Instant completed(UUID jobId, DocumentProcessingStage stage);

    void failed(UUID jobId, DocumentProcessingStage stage);
}
