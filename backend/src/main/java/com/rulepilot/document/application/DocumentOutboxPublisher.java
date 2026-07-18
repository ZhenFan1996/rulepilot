package com.rulepilot.document.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentOutboxPublisher.class);

    private final OutboxEventPublication events;
    private final DocumentProcessingMessagePublisher messages;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public DocumentOutboxPublisher(
            OutboxEventPublication events,
            DocumentProcessingMessagePublisher messages,
            @Value("${rulepilot.document.messaging.batch-size}") int batchSize) {
        this(events, messages, batchSize, Clock.systemUTC());
    }

    DocumentOutboxPublisher(
            OutboxEventPublication events,
            DocumentProcessingMessagePublisher messages,
            int batchSize,
            Clock clock) {
        this.events = events;
        this.messages = messages;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rulepilot.document.messaging.fixed-delay}")
    public void publishReadyEvents() {
        for (var event : events.readyAt(Instant.now(clock), batchSize)) {
            try {
                messages.publish(event.id(), event.eventType(), event.payload());
                events.markPublished(event.id(), Instant.now(clock));
            } catch (RuntimeException exception) {
                LOGGER.warn("Outbox publication failed for eventId={}", event.id(), exception);
            }
        }
    }
}
