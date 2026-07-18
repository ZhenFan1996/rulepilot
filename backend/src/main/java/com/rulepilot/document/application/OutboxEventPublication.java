package com.rulepilot.document.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class OutboxEventPublication {

    private final DocumentOutboxStore outbox;

    public OutboxEventPublication(DocumentOutboxStore outbox) {
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<DocumentOutboxStore.PendingEvent> readyAt(Instant now, int limit) {
        return outbox.findReady(now, limit);
    }

    @Transactional
    public void markPublished(UUID eventId, Instant publishedAt) {
        outbox.markPublished(eventId, publishedAt);
    }
}
