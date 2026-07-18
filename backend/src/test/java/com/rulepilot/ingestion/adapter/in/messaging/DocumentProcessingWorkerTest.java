package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import com.rulepilot.document.DocumentProcessingJobs;
import com.rulepilot.document.DocumentProcessingIdempotency;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.ingestion.application.UploadedDocumentIngestion;
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
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(documentVersionId, jobId);

        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);
        when(idempotency.begin(command, eventId)).thenReturn(true);

        new DocumentProcessingWorker(ingestion, commands, jobs, idempotency).process(message(payload, eventId));

        verify(idempotency).begin(command, eventId);
        verify(jobs).stageStarted(jobId, DocumentProcessingStage.PARSE);
        verify(ingestion).process(documentVersionId, DocumentProcessingStage.PARSE);
        verify(commands).publish(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK));
        verify(idempotency).complete(command);
    }

    @Test
    void rejectsUnsupportedSchemaBeforeIngestion() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        String payload = """
                {"schemaVersion":2,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> new DocumentProcessingWorker(ingestion, commands, jobs, idempotency)
                        .process(message(payload, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document processing command");
    }

    @Test
    void skipsARepeatedBusinessStage() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1","stage":"CHUNK"}
                """.formatted(documentVersionId, jobId);
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);
        when(idempotency.begin(command, eventId)).thenReturn(false);

        new DocumentProcessingWorker(ingestion, commands, jobs, idempotency).process(message(payload, eventId));

        verify(idempotency).begin(command, eventId);
        verifyNoInteractions(ingestion, commands, jobs);
    }

    private Message message(String payload, UUID eventId) {
        var message = new Message(payload.getBytes(StandardCharsets.UTF_8));
        message.getMessageProperties().setHeader("rulepilot-event-id", eventId.toString());
        return message;
    }
}
