package com.rulepilot.document;

import java.util.UUID;

public interface DocumentProcessingJobs {

    void stageStarted(UUID jobId, DocumentProcessingStage stage);

    void completed(UUID jobId, DocumentProcessingStage stage);

    void failed(UUID jobId, DocumentProcessingStage stage);
}
