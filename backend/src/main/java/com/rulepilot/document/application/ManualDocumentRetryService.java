package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingFailures;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ManualDocumentRetryService {

    private final ManualDocumentRetryPreparation preparation;
    private final DocumentProcessingFailures failures;

    public ManualDocumentRetryService(
            ManualDocumentRetryPreparation preparation,
            DocumentProcessingFailures failures) {
        this.preparation = preparation;
        this.failures = failures;
    }

    public RetryAccepted retry(UUID jobId) {
        var command = preparation.prepare(jobId);
        failures.retry(command, 2);
        return new RetryAccepted(jobId, command.documentVersionId(), command.stage().name());
    }

    public record RetryAccepted(UUID jobId, UUID documentVersionId, String stage) {}
}
