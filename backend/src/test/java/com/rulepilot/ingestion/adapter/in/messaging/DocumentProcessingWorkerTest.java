package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.context.annotation.Lazy;

class DocumentProcessingWorkerTest {

    @Test
    void remainsEagerWhenTheWorkerProfileDefersUnusedApplicationBeans() {
        assertThat(DocumentProcessingWorker.class.getAnnotation(Lazy.class))
                .isNotNull()
                .extracting(Lazy::value)
                .isEqualTo(false);
    }

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
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED));
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
    void recordsTheTypedStageAndOutcomeInsideTheRabbitTrace() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);
        when(versions.findVersion(documentVersionId)).thenReturn(Optional.of(
                new DocumentVersionScopeLookup.VersionScope(documentVersionId, UUID.randomUUID(), "UPLOADED", "player")));
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);
        var stopped = new AtomicReference<RecordedObservation>();
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if (!"rulepilot.document.processing.stage".equals(context.getName())) return;
                stopped.set(new RecordedObservation(
                        context.getContextualName(),
                        context.getLowCardinalityKeyValue("stage").getValue(),
                        context.getLowCardinalityKeyValue("outcome").getValue()));
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });

        new DocumentProcessingWorker(
                        ingestion,
                        commands,
                        jobs,
                        idempotency,
                        failures,
                        Mockito.mock(DocumentReadyNotifications.class),
                        versions,
                        new SimpleMeterRegistry(),
                        observations,
                        4)
                .process(message(payload(documentVersionId, jobId, "PARSE"), eventId, 1));

        assertThat(stopped).hasValue(new RecordedObservation("document-processing-parse", "parse", "completed"));
    }

    @Test
    void publishesAReadyNotificationOnlyAfterTheTerminalStageIsDurable() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentReadyNotifications readyNotifications = Mockito.mock(DocumentReadyNotifications.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED);
        Instant readyAt = Instant.parse("2026-08-13T08:00:00Z");
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);
        when(jobs.completed(jobId, DocumentProcessingStage.EMBED)).thenReturn(readyAt);
        var metrics = new SimpleMeterRegistry();

        worker(ingestion, commands, jobs, idempotency, failures, readyNotifications, metrics)
                .process(message(payload(documentVersionId, jobId, "EMBED"), eventId, 1));

        var order = Mockito.inOrder(ingestion, jobs, idempotency, readyNotifications);
        order.verify(ingestion).process(documentVersionId, DocumentProcessingStage.EMBED);
        order.verify(jobs).completed(jobId, DocumentProcessingStage.EMBED);
        order.verify(idempotency).complete(command);
        order.verify(readyNotifications).publish(documentVersionId, readyAt);
    }

    @Test
    void keepsReadyNotificationFailureRecoverableByTheDatabaseScan() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentReadyNotifications readyNotifications = Mockito.mock(DocumentReadyNotifications.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED);
        Instant readyAt = Instant.parse("2026-08-13T08:00:00Z");
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);
        when(jobs.completed(jobId, DocumentProcessingStage.EMBED)).thenReturn(readyAt);
        Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(readyNotifications)
                .publish(documentVersionId, readyAt);
        var metrics = new SimpleMeterRegistry();

        worker(ingestion, commands, jobs, idempotency, failures, readyNotifications, metrics)
                .process(message(payload(documentVersionId, jobId, "EMBED"), eventId, 1));

        verify(idempotency).complete(command);
        verifyNoInteractions(failures);
        assertThat(metrics.counter("rulepilot.document.ready.notification", "outcome", "failed").count())
                .isEqualTo(1);
        assertThat(metrics.counter(
                                "rulepilot.document.processing.attempts",
                                "stage",
                                "embed",
                                "outcome",
                                "completed")
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
    void bridgesALegacyChunkDeliveryToEmbedDuringARollingDeployment() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);
        when(versions.findVersion(documentVersionId)).thenReturn(Optional.of(
                new DocumentVersionScopeLookup.VersionScope(
                        documentVersionId, UUID.randomUUID(), "STRUCTURING", "player")));
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);

        worker(ingestion, commands, jobs, idempotency, failures, versions, new SimpleMeterRegistry())
                .process(message(payload(documentVersionId, jobId, "CHUNK"), eventId, 1));

        verify(ingestion).process(documentVersionId, DocumentProcessingStage.CHUNK);
        verify(commands).publish(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED));
        verify(idempotency).complete(command);
    }

    @Test
    void republishesEmbedFromALegacyChunkRetryThatAlreadyReachedChunking() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var command = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);
        when(versions.findVersion(documentVersionId)).thenReturn(Optional.of(
                new DocumentVersionScopeLookup.VersionScope(
                        documentVersionId, UUID.randomUUID(), "CHUNKING", "player")));
        when(idempotency.begin(command, eventId, 2)).thenReturn(true);

        worker(ingestion, commands, jobs, idempotency, failures, versions, new SimpleMeterRegistry())
                .process(message(payload(documentVersionId, jobId, "CHUNK"), eventId, 2));

        verify(ingestion).process(documentVersionId, DocumentProcessingStage.CHUNK);
        verify(commands).publish(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED));
        verify(idempotency).complete(command);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMBEDDING", "INDEXING", "READY", "FAILED"})
    void acknowledgesALateLegacyChunkWithoutRegressingProgressOrPublishingEmbedAgain(String processingStatus) {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var metrics = new SimpleMeterRegistry();
        when(versions.findVersion(documentVersionId)).thenReturn(Optional.of(
                new DocumentVersionScopeLookup.VersionScope(
                        documentVersionId, UUID.randomUUID(), processingStatus, "player")));
        var command = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);
        when(idempotency.begin(command, eventId, 1)).thenReturn(true);

        worker(ingestion, commands, jobs, idempotency, failures, versions, metrics)
                .process(message(payload(documentVersionId, jobId, "CHUNK"), eventId, 1));

        verify(versions).findVersion(documentVersionId);
        verify(idempotency).begin(command, eventId, 1);
        verify(idempotency).complete(command);
        verifyNoInteractions(ingestion, commands, jobs, failures);
        assertThat(metrics.counter("rulepilot.document.processing.obsolete", "stage", "chunk").count())
                .isEqualTo(1);
    }

    @Test
    void acknowledgesAQueuedStageAfterItsDocumentWasRemoved() {
        UploadedDocumentIngestion ingestion = Mockito.mock(UploadedDocumentIngestion.class);
        DocumentProcessingCommands commands = Mockito.mock(DocumentProcessingCommands.class);
        DocumentProcessingJobs jobs = Mockito.mock(DocumentProcessingJobs.class);
        DocumentProcessingIdempotency idempotency = Mockito.mock(DocumentProcessingIdempotency.class);
        DocumentProcessingFailures failures = Mockito.mock(DocumentProcessingFailures.class);
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var metrics = new SimpleMeterRegistry();
        when(versions.findVersion(documentVersionId)).thenReturn(Optional.empty());

        worker(ingestion, commands, jobs, idempotency, failures, versions, metrics)
                .process(message(payload(documentVersionId, jobId, "PARSE"), eventId, 1));

        verify(versions).findVersion(documentVersionId);
        verifyNoInteractions(ingestion, commands, jobs, idempotency, failures);
        assertThat(metrics
                        .counter("rulepilot.document.processing.orphaned", "stage", "parse")
                        .count())
                .isEqualTo(1);
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
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        when(versions.findVersion(any())).thenAnswer(invocation -> Optional.of(new DocumentVersionScopeLookup.VersionScope(
                invocation.getArgument(0), UUID.randomUUID(), "UPLOADED", "player")));
        return worker(ingestion, commands, jobs, idempotency, failures, versions, metrics);
    }

    private DocumentProcessingWorker worker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures,
            DocumentVersionScopeLookup versions,
            SimpleMeterRegistry metrics) {
        return new DocumentProcessingWorker(
                ingestion,
                commands,
                jobs,
                idempotency,
                failures,
                Mockito.mock(DocumentReadyNotifications.class),
                versions,
                metrics,
                ObservationRegistry.NOOP,
                4);
    }

    private DocumentProcessingWorker worker(
            UploadedDocumentIngestion ingestion,
            DocumentProcessingCommands commands,
            DocumentProcessingJobs jobs,
            DocumentProcessingIdempotency idempotency,
            DocumentProcessingFailures failures,
            DocumentReadyNotifications readyNotifications,
            SimpleMeterRegistry metrics) {
        DocumentVersionScopeLookup versions = Mockito.mock(DocumentVersionScopeLookup.class);
        when(versions.findVersion(any())).thenAnswer(invocation -> Optional.of(new DocumentVersionScopeLookup.VersionScope(
                invocation.getArgument(0), UUID.randomUUID(), "UPLOADED", "player")));
        return new DocumentProcessingWorker(
                ingestion,
                commands,
                jobs,
                idempotency,
                failures,
                readyNotifications,
                versions,
                metrics,
                ObservationRegistry.NOOP,
                4);
    }

    private record RecordedObservation(String contextualName, String stage, String outcome) {}

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
