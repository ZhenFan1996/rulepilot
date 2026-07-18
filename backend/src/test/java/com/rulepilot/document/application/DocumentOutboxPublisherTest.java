package com.rulepilot.document.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DocumentOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void marksEventPublishedOnlyAfterMessagePublicationSucceeds() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = new DocumentOutboxStore.PendingEvent(eventId, "DocumentProcessingRequested", "{}");
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));

        new DocumentOutboxPublisher(events, messages, 20, Clock.fixed(NOW, ZoneOffset.UTC))
                .publishReadyEvents();

        verify(messages).publish(eventId, event.eventType(), event.payload());
        verify(events).markPublished(eventId, NOW);
    }

    @Test
    void leavesEventUnpublishedWhenBrokerPublicationFails() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = new DocumentOutboxStore.PendingEvent(eventId, "DocumentProcessingRequested", "{}");
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));
        Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(messages)
                .publish(eventId, event.eventType(), event.payload());

        new DocumentOutboxPublisher(events, messages, 20, Clock.fixed(NOW, ZoneOffset.UTC))
                .publishReadyEvents();

        verify(events, never()).markPublished(eventId, NOW);
    }
}
