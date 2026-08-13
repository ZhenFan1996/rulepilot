package com.rulepilot.teaching.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.teaching.application.ImportedRulebookTeachingLauncher;
import com.rulepilot.teaching.application.UploadedRulebookTeachingLauncher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Wakes the API-side durable handoff claim as soon as the PDF worker commits document readiness. */
@Component
@Profile("!test")
@Lazy(false)
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentReadyTeachingHandoffListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentReadyTeachingHandoffListener.class);

    private final ObjectMapper json = new ObjectMapper();
    private final ImportedRulebookTeachingLauncher imported;
    private final UploadedRulebookTeachingLauncher uploaded;
    private final MeterRegistry metrics;
    private final Clock clock;

    @Autowired
    public DocumentReadyTeachingHandoffListener(
            ImportedRulebookTeachingLauncher imported,
            UploadedRulebookTeachingLauncher uploaded,
            MeterRegistry metrics) {
        this(imported, uploaded, metrics, Clock.systemUTC());
    }

    DocumentReadyTeachingHandoffListener(
            ImportedRulebookTeachingLauncher imported,
            UploadedRulebookTeachingLauncher uploaded,
            MeterRegistry metrics,
            Clock clock) {
        this.imported = imported;
        this.uploaded = uploaded;
        this.metrics = metrics;
        this.clock = clock;
    }

    @RabbitListener(queues = "${rulepilot.document.ready-notification.queue}")
    public void onReady(Message message) {
        ReadyNotification notification = read(message);
        boolean dispatched = dispatch(
                "imported",
                notification.documentVersionId(),
                () -> imported.dispatchReadyHandoffs(notification.documentVersionId()));
        dispatched &= dispatch(
                "uploaded",
                notification.documentVersionId(),
                () -> uploaded.dispatchReadyHandoffs(notification.documentVersionId()));
        recordWakeupLag(notification.readyAt());
        metrics.counter(
                        "rulepilot.teaching.handoff.wakeup",
                        "outcome",
                        dispatched ? "dispatched" : "fallback_required")
                .increment();
    }

    private boolean dispatch(String handoffType, UUID documentVersionId, Runnable dispatcher) {
        try {
            dispatcher.run();
            return true;
        } catch (RuntimeException failure) {
            // The event is only a latency hint. A database failure must not create a hot Rabbit redelivery loop;
            // the isolated scheduled reconciliation lane retries the same durable waiting intent.
            LOGGER.warn(
                    "Document READY wake-up could not dispatch {} handoff for documentVersionId={}; scheduled reconciliation will recover it",
                    handoffType,
                    documentVersionId,
                    failure);
            return false;
        }
    }

    private ReadyNotification read(Message message) {
        try {
            JsonNode payload = json.readTree(message.getBody());
            if (payload.path("schemaVersion").asInt() != 1) {
                throw new IllegalArgumentException("unsupported document READY notification schema");
            }
            UUID documentVersionId = UUID.fromString(payload.path("documentVersionId").asText());
            Instant readyAt = Instant.parse(payload.path("readyAt").asText());
            return new ReadyNotification(documentVersionId, readyAt);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException("Document READY notification is invalid", invalid);
        }
    }

    private void recordWakeupLag(Instant readyAt) {
        Duration lag = Duration.between(readyAt, Instant.now(clock));
        if (lag.isNegative()) lag = Duration.ZERO;
        Timer.builder("rulepilot.teaching.handoff.ready_to_dispatch")
                .description("Elapsed time from persisted document readiness to API handoff dispatch")
                .register(metrics)
                .record(lag);
    }

    private record ReadyNotification(UUID documentVersionId, Instant readyAt) {}
}
