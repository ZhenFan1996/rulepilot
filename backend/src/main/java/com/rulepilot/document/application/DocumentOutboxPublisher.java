package com.rulepilot.document.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
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
    private final MeterRegistry metrics;
    private final DocumentTraceContextBridge traceContexts;
    private final int batchSize;
    private final Clock clock;
    private boolean publishing;
    private boolean publishAgain;

    @Autowired
    public DocumentOutboxPublisher(
            OutboxEventPublication events,
            DocumentProcessingMessagePublisher messages,
            MeterRegistry metrics,
            DocumentTraceContextBridge traceContexts,
            @Value("${rulepilot.document.messaging.batch-size}") int batchSize) {
        this(events, messages, metrics, traceContexts, batchSize, Clock.systemUTC());
    }

    DocumentOutboxPublisher(
            OutboxEventPublication events,
            DocumentProcessingMessagePublisher messages,
            MeterRegistry metrics,
            int batchSize,
            Clock clock) {
        this(events, messages, metrics, DocumentTraceContextBridge.noop(), batchSize, clock);
    }

    DocumentOutboxPublisher(
            OutboxEventPublication events,
            DocumentProcessingMessagePublisher messages,
            MeterRegistry metrics,
            DocumentTraceContextBridge traceContexts,
            int batchSize,
            Clock clock) {
        this.events = events;
        this.messages = messages;
        this.metrics = metrics;
        this.traceContexts = traceContexts;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rulepilot.document.messaging.fixed-delay}")
    public void publishReadyEvents() {
        requestPublication();
    }

    /** After-commit latency hint; the scheduled scan remains the durable recovery path. */
    public void publishCommittedEvents() {
        requestPublication();
    }

    private void requestPublication() {
        synchronized (this) {
            if (publishing) {
                publishAgain = true;
                return;
            }
            publishing = true;
        }
        try {
            while (true) {
                synchronized (this) {
                    publishAgain = false;
                }
                publishBatch();
                synchronized (this) {
                    if (!publishAgain) {
                        publishing = false;
                        return;
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                publishing = false;
            }
            throw failure;
        }
    }

    private void publishBatch() {
        for (var event : events.readyAt(Instant.now(clock), batchSize)) {
            String outcome = "failed";
            Instant attemptedAt = Instant.now(clock);
            try (var trace = traceContexts.open(event.traceHeaders(), "document.outbox.publish")) {
                try {
                    messages.publish(event.id(), event.eventType(), event.payload());
                    events.markPublished(event.id(), Instant.now(clock));
                    outcome = "published";
                } catch (RuntimeException exception) {
                    trace.error(exception);
                    LOGGER.warn("Outbox publication failed for eventId={}", event.id(), exception);
                } finally {
                    trace.outcome(outcome);
                    Duration queued = Duration.between(event.occurredAt(), attemptedAt);
                    if (queued.isNegative()) queued = Duration.ZERO;
                    Timer.builder("rulepilot.document.outbox.queued_to_publish")
                            .description("Elapsed time from durable document outbox enqueue to a publication attempt")
                            .tag("outcome", outcome)
                            .register(metrics)
                            .record(queued);
                }
            }
        }
    }
}
