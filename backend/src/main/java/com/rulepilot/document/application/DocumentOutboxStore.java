package com.rulepilot.document.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentOutboxStore {

    List<PendingEvent> findReady(Instant now, int limit);

    void markPublished(UUID eventId, Instant publishedAt);

    record PendingEvent(UUID id, String eventType, String payload) {}
}
