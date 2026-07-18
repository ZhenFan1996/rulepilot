package com.rulepilot.document.application;

import java.time.Instant;
import java.util.UUID;

public interface DocumentProcessingQueue {

    void enqueue(UUID documentVersionId, Instant occurredAt);
}
