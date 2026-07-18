package com.rulepilot.ingestion.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import com.rulepilot.document.DocumentProcessingJobs;
import com.rulepilot.document.DocumentProcessingIdempotency;
import com.rulepilot.document.DocumentProcessingFailures;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.RetryableDocumentProcessingException;
import com.rulepilot.ingestion.application.UploadedDocumentIngestion;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentProcessingWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessingWorker.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UploadedDocumentIngestion ingestion;
    private final DocumentProcessingCommands commands;
    private final DocumentProcessingJobs jobs;
    private final DocumentProcessingIdempotency idempotency;
    private final DocumentProcessingFailures failures;
    private final int maxAttempts;

    public DocumentProcessingWorker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures,
            @Value("${rulepilot.document.messaging.max-attempts}") int maxAttempts) {
        this.ingestion = ingestion;
        this.commands = commands;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.failures = failures;
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
        if (!idempotency.begin(command, eventId, attempt)) {
            LOGGER.info(
                    "Skipping duplicate document processing delivery for documentVersionId={}, stage={}, pipelineVersion={}",
                    command.documentVersionId(),
                    command.stage(),
                    command.pipelineVersion());
            return;
        }
        try {
            jobs.stageStarted(command.processingJobId(), command.stage());
            ingestion.process(command.documentVersionId(), command.stage());
            var next = command.nextStage();
            if (next == null) {
                jobs.completed(command.processingJobId(), command.stage());
            } else {
                commands.publish(next);
            }
            idempotency.complete(command);
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
                return;
            }
            failures.deadLetter(command, attempt, errorCode);
            ingestion.fail(command.documentVersionId());
            try {
                jobs.failed(command.processingJobId(), command.stage());
            } catch (RuntimeException statusException) {
                LOGGER.error("Could not mark processing job failed for jobId={}", command.processingJobId(), statusException);
            }
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
