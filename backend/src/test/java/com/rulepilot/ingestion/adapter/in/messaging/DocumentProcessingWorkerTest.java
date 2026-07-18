package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import com.rulepilot.document.DocumentProcessingFailures;
import com.rulepilot.document.DocumentProcessingJobs;
import com.rulepilot.document.DocumentProcessingIdempotency;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.RetryableDocumentProcessingException;
import com.rulepilot.ingestion.application.UploadedDocumentIngestion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;

class DocumentProcessingWorkerTest {

    @Test
    void dispatchesSupportedVersionToIngestion() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var metrics = new SimpleMeterRegistry();
        String payload = """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(documentVersionId, jobId);

        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);

        worker(ingestion, commands, jobs, idempotency, failures, metrics).process(message(payload, eventId, 1));

        verify(idempotency).begin(command, eventId, 1);
        verify(jobs).stageStarted(jobId, DocumentProcessingStage.PARSE);
        verify(ingestion).process(documentVersionId, DocumentProcessingStage.PARSE);
        verify(commands).publish(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK));
        verify(idempotency).complete(command);
        assertThat(metrics
                        .counter("rulepilot.document.processing.attempts", "stage", "parse", "outcome", "completed")
                        .count())
                .isEqualTo(1);
        assertThat(metrics
                        .timer("rulepilot.document.processing.stage.duration", "stage", "parse", "outcome", "completed")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedSchemaBeforeIngestion() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        String payload = """
                {"schemaVersion":2,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> worker(ingestion, commands, jobs, idempotency, failures)
                        .process(message(payload, UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document processing command");
    }

    @Test
    void skipsARepeatedBusinessStage() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1","stage":"CHUNK"}
                """.formatted(documentVersionId, jobId);
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);
        when(idempotency.begin(command, eventId, 1)).thenReturn(false);

        worker(ingestion, commands, jobs, idempotency, failures).process(message(payload, eventId, 1));

        verify(idempotency).begin(command, eventId, 1);
        verifyNoInteractions(ingestion, commands, jobs, failures);
    }

    @Test
    void routesTransientFailureToNextRetryAttempt() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);
        when(idempotency.begin(command, eventId, 2)).thenReturn(true);
        Mockito.doThrow(new RetryableDocumentProcessingException("storage timeout", new RuntimeException()))
                .when(ingestion)
                .process(documentVersionId, DocumentProcessingStage.PARSE);
        String payload = payload(documentVersionId, jobId, "PARSE");

        worker(ingestion, commands, jobs, idempotency, failures).process(message(payload, eventId, 2));

        verify(idempotency).fail(command, "TRANSIENT_FAILURE");
        verify(failures).retry(command, 3);
    }

    @Test
    void routesPermanentFailureDirectlyToDeadLetterQueue() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);
        Mockito.doThrow(new IllegalArgumentException("invalid PDF"))
                .when(ingestion)
                .process(documentVersionId, DocumentProcessingStage.PARSE);

        worker(ingestion, commands, jobs, idempotency, failures)
                .process(message(payload(documentVersionId, jobId, "PARSE"), eventId, 1));

        verify(failures).deadLetter(command, 1, "PERMANENT_FAILURE");
        verify(ingestion).fail(documentVersionId);
        verify(jobs).failed(jobId, DocumentProcessingStage.PARSE);
    }

    @Test
    void deadLettersAnExhaustedTransientFailure() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED);
        when(idempotency.begin(command, eventId, 4)).thenReturn(true);
        Mockito.doThrow(new RetryableDocumentProcessingException("provider timeout", new RuntimeException()))
                .when(ingestion)
                .process(documentVersionId, DocumentProcessingStage.EMBED);

        worker(ingestion, commands, jobs, idempotency, failures)
                .process(message(payload(documentVersionId, jobId, "EMBED"), eventId, 4));

        verify(idempotency).fail(command, "RETRY_EXHAUSTED");
        verify(failures).deadLetter(command, 4, "RETRY_EXHAUSTED");
        verify(ingestion).fail(documentVersionId);
        verify(jobs).failed(jobId, DocumentProcessingStage.EMBED);
    }

    private DocumentProcessingWorker worker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures) {
        return worker(ingestion, commands, jobs, idempotency, failures, new SimpleMeterRegistry());
    }

    private DocumentProcessingWorker worker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures,
            SimpleMeterRegistry metrics) {
        return new DocumentProcessingWorker(
                ingestion, commands, jobs, idempotency, failures, metrics, 4);
    }

    private String payload(UUID documentVersionId, UUID jobId, String stage) {
        return """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1","stage":"%s"}
                """.formatted(documentVersionId, jobId, stage);
    }

    private Message message(String payload, UUID eventId, int attempt) {
        var message = new Message(payload.getBytes(StandardCharsets.UTF_8));
        message.getMessageProperties().setHeader("rulepilot-event-id", eventId.toString());
        message.getMessageProperties().setHeader("rulepilot-attempt", (long) attempt);
        return message;
    }
}
