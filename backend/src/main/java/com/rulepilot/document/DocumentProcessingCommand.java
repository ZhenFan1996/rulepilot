package com.rulepilot.document;

import java.util.UUID;

public record DocumentProcessingCommand(
        int schemaVersion,
        UUID documentVersionId,
        UUID processingJobId,
        String pipelineVersion,
        DocumentProcessingStage stage) {

    public DocumentProcessingCommand {
        if (schemaVersion != 1
                || documentVersionId == null
                || processingJobId == null
                || !"v1".equals(pipelineVersion)
                || stage == null) {
            throw new IllegalArgumentException("Unsupported document processing command");
        }
    }

    public DocumentProcessingCommand nextStage() {
        var next = stage.next();
        return next == null
                ? null
                : new DocumentProcessingCommand(
                        schemaVersion, documentVersionId, processingJobId, pipelineVersion, next);
    }
}
