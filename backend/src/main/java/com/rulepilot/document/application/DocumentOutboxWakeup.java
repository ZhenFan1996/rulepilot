package com.rulepilot.document.application;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
class DocumentOutboxWakeup {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentOutboxWakeup.class);

    private final DocumentOutboxPublisher publisher;
    private final TaskExecutor executor;

    DocumentOutboxWakeup(
            DocumentOutboxPublisher publisher,
            @Qualifier("documentOutboxWakeupExecutor") TaskExecutor executor) {
        this.publisher = publisher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterCommit(DocumentOutboxQueued ignored) {
        try {
            executor.execute(() -> {
                try {
                    publisher.publishCommittedEvents();
                } catch (RuntimeException failure) {
                    LOGGER.warn("Immediate document outbox publication failed; scheduled publication will retry", failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // The outbox row is committed. A saturated hint lane safely falls back to the bounded scheduled scan.
            LOGGER.warn("Document outbox wake-up lane is full; scheduled publication will recover the committed event");
        }
    }
}
