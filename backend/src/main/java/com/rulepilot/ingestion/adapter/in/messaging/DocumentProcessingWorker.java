package com.rulepilot.ingestion.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import com.rulepilot.document.DocumentProcessingFailures;
import com.rulepilot.document.DocumentProcessingJobs;
import com.rulepilot.document.DocumentProcessingIdempotency;
import com.rulepilot.document.DocumentReadyNotifications;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RetryableDocumentProcessingException;
import com.rulepilot.ingestion.application.UploadedDocumentIngestion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Lazy(false)
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentProcessingWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessingWorker.class);
    private static final Set<String> LEGACY_CHUNK_OBSOLETE_STATUSES =
            Set.of("EMBEDDING", "INDEXING", "READY", "FAILED");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UploadedDocumentIngestion ingestion;
    private final DocumentProcessingCommands commands;
    private final DocumentProcessingJobs jobs;
    private final DocumentProcessingIdempotency idempotency;
    private final DocumentProcessingFailures failures;
    private final DocumentReadyNotifications readyNotifications;
    private final DocumentVersionScopeLookup versions;
    private final MeterRegistry metrics;
    private final int maxAttempts;

    public DocumentProcessingWorker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures,
            DocumentReadyNotifications readyNotifications,
            DocumentVersionScopeLookup versions,
            MeterRegistry metrics,
            @Value("${rulepilot.document.messaging.max-attempts}") int maxAttempts) {
        this.ingestion = ingestion;
        this.commands = commands;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.failures = failures;
        this.readyNotifications = readyNotifications;
        this.versions = versions;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = "${rulepilot.document.messaging.queue}")
    public void process(Message message) {
        try {
            execute(readCommand(message), eventId(message), attempt(message));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Document processing message is not valid JSON", exception);
        }
    }

    private int attempt(Message message) {
        Object value = message.getMessageProperties().getHeader("rulepilot-attempt");
        if (value == null) {
            return 1;
        }
        if (!(value instanceof Number number) || number.intValue() < 1) {
            throw new IllegalArgumentException("Document processing message has an invalid attempt");
        }
        return number.intValue();
    }

    private UUID eventId(Message message) {
        String value = message.getMessageProperties().getHeader("rulepilot-event-id");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document processing message is missing event id");
        }
        return UUID.fromString(value);
    }

    private DocumentProcessingCommand readCommand(Message message) throws IOException {
        JsonNode payload = objectMapper.readTree(message.getBody());
        String stage = payload.path("stage").asText("PARSE");
        return new DocumentProcessingCommand(
                payload.path("schemaVersion").asInt(),
                UUID.fromString(requireText(payload, "documentVersionId")),
                UUID.fromString(requireText(payload, "processingJobId")),
                requireText(payload, "pipelineVersion"),
                DocumentProcessingStage.valueOf(stage));
    }

    private void execute(DocumentProcessingCommand command, UUID eventId, int attempt) {
        var version = versions.findVersion(command.documentVersionId());
        if (version.isEmpty()) {
            metrics.counter(
                            "rulepilot.document.processing.orphaned",
                            "stage",
                            command.stage().name().toLowerCase())
                    .increment();
            LOGGER.info(
                    "Acknowledging document processing delivery after its document was removed for documentVersionId={}, stage={}",
                    command.documentVersionId(),
                    command.stage());
            return;
        }
        if (!idempotency.begin(command, eventId, attempt)) {
            metrics.counter(
                            "rulepilot.document.processing.duplicates",
                            "stage",
                            command.stage().name().toLowerCase())
                    .increment();
            LOGGER.info(
                    "Skipping duplicate document processing delivery for documentVersionId={}, stage={}, pipelineVersion={}",
                    command.documentVersionId(),
                    command.stage(),
                    command.pipelineVersion());
            return;
        }
        if (command.stage() == DocumentProcessingStage.CHUNK
                && LEGACY_CHUNK_OBSOLETE_STATUSES.contains(version.orElseThrow().processingStatus())) {
            idempotency.complete(command);
            metrics.counter(
                            "rulepilot.document.processing.obsolete",
                            "stage",
                            command.stage().name().toLowerCase())
                    .increment();
            LOGGER.info(
                    "Acknowledging obsolete legacy CHUNK delivery for documentVersionId={}, processingStatus={}",
                    command.documentVersionId(),
                    version.orElseThrow().processingStatus());
            return;
        }
        Timer.Sample duration = Timer.start(metrics);
        String outcome = "internal_error";
        try {
            jobs.stageStarted(command.processingJobId(), command.stage());
            ingestion.process(command.documentVersionId(), command.stage());
            var next = command.nextStage();
            Instant readyAt = null;
            if (next == null) {
                readyAt = jobs.completed(command.processingJobId(), command.stage());
            } else {
                commands.publish(next);
            }
            idempotency.complete(command);
            if (next == null) notifyReady(command.documentVersionId(), readyAt);
            outcome = "completed";
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Document processing stage failed for jobId={}, documentVersionId={}, stage={}",
                    command.processingJobId(),
                    command.documentVersionId(),
                    command.stage(),
                    exception);
            boolean retryable = exception instanceof RetryableDocumentProcessingException;
            String errorCode = retryable
                    ? (attempt < maxAttempts ? "TRANSIENT_FAILURE" : "RETRY_EXHAUSTED")
                    : "PERMANENT_FAILURE";
            idempotency.fail(command, errorCode);
            if (retryable && attempt < maxAttempts) {
                failures.retry(command, attempt + 1);
                outcome = "retry_scheduled";
                return;
            }
            failures.deadLetter(command, attempt, errorCode);
            outcome = "dead_letter";
            ingestion.fail(command.documentVersionId());
            try {
                jobs.failed(command.processingJobId(), command.stage());
            } catch (RuntimeException statusException) {
                LOGGER.error("Could not mark processing job failed for jobId={}", command.processingJobId(), statusException);
            }
        } finally {
            metrics.counter(
                            "rulepilot.document.processing.attempts",
                            "stage",
                            command.stage().name().toLowerCase(),
                            "outcome",
                            outcome)
                    .increment();
            duration.stop(Timer.builder("rulepilot.document.processing.stage.duration")
                    .description("Document processing stage duration")
                    .tag("stage", command.stage().name().toLowerCase())
                    .tag("outcome", outcome)
                    .register(metrics));
        }
    }

    private void notifyReady(UUID documentVersionId, Instant readyAt) {
        try {
            readyNotifications.publish(documentVersionId, readyAt);
            metrics.counter("rulepilot.document.ready.notification", "outcome", "published").increment();
        } catch (RuntimeException failure) {
            // READY and the waiting Teaching intent are already durable. The API reconciliation scan is the retry
            // mechanism, so a best-effort wake-up must never dead-letter successful document processing.
            metrics.counter("rulepilot.document.ready.notification", "outcome", "failed").increment();
            LOGGER.warn(
                    "Document READY wake-up failed for documentVersionId={}; scheduled reconciliation will recover it",
                    documentVersionId,
                    failure);
        }
    }

    private String requireText(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Document processing message is missing " + field);
        }
        return value;
    }
}
