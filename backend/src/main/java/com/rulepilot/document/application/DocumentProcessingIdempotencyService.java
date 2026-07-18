package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingIdempotency;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class DocumentProcessingIdempotencyService implements DocumentProcessingIdempotency {

    private final DocumentProcessingDeduplicationStore executions;
    private final Clock clock = Clock.systemUTC();

    public DocumentProcessingIdempotencyService(DocumentProcessingDeduplicationStore executions) {
        this.executions = executions;
    }

    @Override
    @Transactional
    public boolean begin(DocumentProcessingCommand command, UUID eventId) {
        return executions.begin(command, eventId, Instant.now(clock));
    }

    @Override
    @Transactional
    public void complete(DocumentProcessingCommand command) {
        executions.update(command, "COMPLETED", Instant.now(clock));
    }

    @Override
    @Transactional
    public void fail(DocumentProcessingCommand command) {
        executions.update(command, "FAILED", Instant.now(clock));
    }
}
