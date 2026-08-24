package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        var metrics = new SimpleMeterRegistry();
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));

        new DocumentOutboxPublisher(events, messages, metrics, 20, Clock.fixed(NOW, ZoneOffset.UTC))
                .publishReadyEvents();

        verify(messages).publish(eventId, event.eventType(), event.payload());
        verify(events).markPublished(eventId, NOW);
        assertThat(metrics.find("rulepilot.document.outbox.queued_to_publish")
                        .tag("outcome", "published")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(250);
    }

    @Test
    void leavesEventUnpublishedWhenBrokerPublicationFails() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));
        Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(messages)
                .publish(eventId, event.eventType(), event.payload());

        new DocumentOutboxPublisher(events, messages, new SimpleMeterRegistry(), 20, Clock.fixed(NOW, ZoneOffset.UTC))
                .publishReadyEvents();

        verify(events, never()).markPublished(eventId, NOW);
    }

    @Test
    void restoresThePersistedContextAroundPublicationAndRecordsTheStableOutcome() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        DocumentTraceContextBridge traceContexts = Mockito.mock(DocumentTraceContextBridge.class);
        DocumentTraceContextBridge.Scope scope = Mockito.mock(DocumentTraceContextBridge.Scope.class);
        var headers = new DocumentOutboxStore.TraceHeaders(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");
        UUID eventId = UUID.randomUUID();
        var event = new DocumentOutboxStore.PendingEvent(
                eventId, "DocumentProcessingRequested", "{}", NOW.minusMillis(250), headers);
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));
        when(traceContexts.open(headers, "document.outbox.publish")).thenReturn(scope);

        new DocumentOutboxPublisher(
                        events,
                        messages,
                        new SimpleMeterRegistry(),
                        traceContexts,
                        20,
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .publishReadyEvents();

        verify(traceContexts).open(headers, "document.outbox.publish");
        verify(scope).outcome("published");
        verify(scope).close();
    }

    @Test
    void anAfterCommitWakeupPublishesTheNewlyCommittedEventWithoutWaitingForTheScheduledScan() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(events.readyAt(NOW, 20)).thenReturn(List.of(event));
        var publisher = new DocumentOutboxPublisher(
                events, messages, new SimpleMeterRegistry(), 20, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishCommittedEvents();

        verify(messages).publish(eventId, event.eventType(), event.payload());
        verify(events).markPublished(eventId, NOW);
    }

    @Test
    void aWakeupArrivingDuringPublicationTriggersOneFollowUpDrainWithoutRepublishingTheEvent() throws InterruptedException {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(events.readyAt(NOW, 20))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return List.of(event);
                })
                .thenReturn(List.of());
        var publisher = new DocumentOutboxPublisher(
                events, messages, new SimpleMeterRegistry(), 20, Clock.fixed(NOW, ZoneOffset.UTC));
        Thread first = Thread.ofVirtual().start(publisher::publishCommittedEvents);
        assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofVirtual().start(publisher::publishCommittedEvents);

        release.countDown();
        first.join();
        second.join();

        verify(events, times(2)).readyAt(NOW, 20);
        verify(messages, times(1)).publish(eventId, event.eventType(), event.payload());
    }

    @Test
    void aStoreReadFailureDoesNotPermanentlySuppressLaterPublicationAttempts() {
        OutboxEventPublication events = Mockito.mock(OutboxEventPublication.class);
        DocumentProcessingMessagePublisher messages = Mockito.mock(DocumentProcessingMessagePublisher.class);
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(events.readyAt(NOW, 20))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of(event));
        var publisher = new DocumentOutboxPublisher(
                events, messages, new SimpleMeterRegistry(), 20, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(publisher::publishCommittedEvents)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        publisher.publishCommittedEvents();

        verify(events, times(2)).readyAt(NOW, 20);
        verify(messages).publish(eventId, event.eventType(), event.payload());
        verify(events).markPublished(eventId, NOW);
    }

    private DocumentOutboxStore.PendingEvent event(UUID eventId) {
        return new DocumentOutboxStore.PendingEvent(
                eventId, "DocumentProcessingRequested", "{}", NOW.minusMillis(250));
    }
}
