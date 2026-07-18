package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessingCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ManualDocumentRetryPreparation {

    private final DocumentProcessingDeduplicationStore executions;
    private final DocumentProcessing documents;
    private final Clock clock = Clock.systemUTC();

    public ManualDocumentRetryPreparation(
            DocumentProcessingDeduplicationStore executions,
            DocumentProcessing documents) {
        this.executions = executions;
        this.documents = documents;
    }

    @Transactional
    public DocumentProcessingCommand prepare(UUID jobId) {
        var command = executions.findFailed(jobId)
                .orElseThrow(() -> new IllegalArgumentException("failed processing job does not exist"));
        documents.prepareRetry(command.documentVersionId(), command.stage());
        executions.resetJob(jobId, command.stage().name(), Instant.now(clock));
        return command;
    }
}
