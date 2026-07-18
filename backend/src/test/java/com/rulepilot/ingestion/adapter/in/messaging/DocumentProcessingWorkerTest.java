package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import com.rulepilot.document.DocumentProcessingJobs;
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
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = """
                {"schemaVersion":1,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(documentVersionId, jobId);

        new DocumentProcessingWorker(ingestion, commands, jobs).process(message(payload));

        verify(jobs).stageStarted(jobId, DocumentProcessingStage.PARSE);
        verify(ingestion).process(documentVersionId, DocumentProcessingStage.PARSE);
        verify(commands).publish(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK));
    }

    @Test
    void rejectsUnsupportedSchemaBeforeIngestion() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        String payload = """
                {"schemaVersion":2,"documentVersionId":"%s","processingJobId":"%s","pipelineVersion":"v1"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> new DocumentProcessingWorker(ingestion, commands, jobs).process(message(payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document processing command");
    }

    private Message message(String payload) {
        return new Message(payload.getBytes(StandardCharsets.UTF_8));
    }
}
