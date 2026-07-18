package com.rulepilot.document.application;

import java.util.UUID;

public interface DocumentProcessingMessagePublisher {

    void publish(UUID eventId, String eventType, String payload);
}
