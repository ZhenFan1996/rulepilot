package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class OfficialRulebookImportJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String SOURCE = "https://publisher.example/rules.pdf";

    @Test
    void persistsRealDownloadStagesAndCompletesWithTheUploadedVersion() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        UUID versionId = UUID.randomUUID();
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(1_024L);
            progress.downloaded(512, 1_024L);
            progress.downloaded(1_024, 1_024L);
            progress.verifying();
            progress.saving();
            return uploadResult(versionId);
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var completed = service.requireOwned(launch.job().id(), "alice");

        assertThat(launch.reused()).isFalse();
        assertThat(completed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.COMPLETED);
        assertThat(completed.downloadedBytes()).isEqualTo(1_024);
        assertThat(completed.totalBytes()).isEqualTo(1_024);
        assertThat(completed.documentVersionId()).isEqualTo(versionId);
        assertThat(jobs.stages).containsExactly(
                OfficialRulebookImportJob.Stage.CONNECTING,
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                OfficialRulebookImportJob.Stage.SAVING,
                OfficialRulebookImportJob.Stage.COMPLETED);
    }

    @Test
    void reusesAnActiveDownloadForTheSameOwnerAndSource() {
        FakeJobs jobs = new FakeJobs();
        var active = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(active);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(command(), "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.job().id()).isEqualTo(active.id());
        verify(executor, never()).execute(any());
        verify(imports, never()).importRulebook(any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void exposesOversizedPdfCompressionAsARecoverableBackgroundStage() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        UUID versionId = UUID.randomUUID();
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(60_000_000L);
            progress.downloaded(60_000_000L, 60_000_000L);
            progress.compressing();
            progress.verifying();
            progress.saving();
            return uploadResult(versionId);
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var completed = service.requireOwned(launch.job().id(), "alice");

        assertThat(completed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.COMPLETED);
        assertThat(jobs.stages).containsSubsequence(
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.COMPRESSING,
                OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                OfficialRulebookImportJob.Stage.SAVING,
                OfficialRulebookImportJob.Stage.COMPLETED);
    }

    @Test
    void recordsAStableFailureCodeWhenTheBackgroundFetchFails() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        when(imports.importRulebook(any(), anyString(), any(), eq(SOURCE), anyBoolean(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("not a PDF"));
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var failed = service.requireOwned(launch.job().id(), "alice");

        assertThat(failed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(failed.errorCode()).isEqualTo("INVALID_PDF_SOURCE");
        assertThat(failed.completedAt()).isEqualTo(NOW);
    }

    @Test
    void preservesAnInteractiveBrowserRequirementAsARecoverableSourceState() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        when(imports.importRulebook(any(), anyString(), any(), eq(SOURCE), anyBoolean(), anyString(), any()))
                .thenThrow(new OfficialRulebookSourceAccessException(
                        OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED,
                        "BGG sign-in required"));
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var failed = service.requireOwned(launch.job().id(), "alice");

        assertThat(failed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_BROWSER_REQUIRED");
    }

    @Test
    void turnsExecutorSaturationIntoAVisibleTerminalJob() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor rejected = task -> { throw new TaskRejectedException("full"); };
        OfficialRulebookImportJobService service = service(jobs, imports, rejected);

        var launch = service.enqueue(command(), "alice");

        assertThat(launch.job().stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(launch.job().errorCode()).isEqualTo("IMPORT_QUEUE_FULL");
    }

    private OfficialRulebookImportJobService service(
            FakeJobs jobs, OfficialRulebookImportService imports, TaskExecutor executor) {
        return new OfficialRulebookImportJobService(
                jobs, imports, executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OfficialRulebookImportJobService.Command command() {
        return new OfficialRulebookImportJobService.Command(
                null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, true);
    }

    private UploadRuleDocumentService.UploadResult uploadResult(UUID versionId) {
        UUID documentId = UUID.randomUUID();
        RuleDocument document = RuleDocument.create(
                null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, null, "alice", NOW);
        DocumentVersion version = new DocumentVersion(
                versionId, documentId, 1, "official-rulebook.pdf", "documents/example.pdf",
                "a".repeat(64), 1_024, "application/pdf", ProcessingStatus.UPLOADED, NOW);
        return new UploadRuleDocumentService.UploadResult(document, version, false);
    }

    private static final class FakeJobs implements OfficialRulebookImportJobRepository {
        private final Map<UUID, OfficialRulebookImportJob> values = new LinkedHashMap<>();
        private final List<OfficialRulebookImportJob.Stage> stages = new ArrayList<>();

        @Override public void insert(OfficialRulebookImportJob job) { values.put(job.id(), job); }

        @Override
        public Optional<OfficialRulebookImportJob> findOwned(UUID jobId, String ownerUsername) {
            return Optional.ofNullable(values.get(jobId)).filter(job -> job.ownerUsername().equals(ownerUsername));
        }

        @Override
        public Optional<OfficialRulebookImportJob> findActiveOwnedBySource(String ownerUsername, String sourceUrl) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl) && !job.stage().terminal())
                    .findFirst();
        }

        @Override
        public List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit) {
            return values.values().stream().filter(job -> job.ownerUsername().equals(ownerUsername)).limit(limit).toList();
        }

        @Override
        public void updateProgress(
                UUID jobId, OfficialRulebookImportJob.Stage stage, long downloadedBytes, Long totalBytes, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, stage, downloadedBytes, totalBytes, null, false, null, now, null));
            stages.add(stage);
        }

        @Override
        public void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.COMPLETED,
                    job.downloadedBytes(), job.totalBytes(), documentVersionId, duplicate, null, now, now));
            stages.add(OfficialRulebookImportJob.Stage.COMPLETED);
        }

        @Override
        public void fail(UUID jobId, String errorCode, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.FAILED,
                    job.downloadedBytes(), job.totalBytes(), null, false, errorCode, now, now));
            stages.add(OfficialRulebookImportJob.Stage.FAILED);
        }

        @Override public int failInterrupted(Instant now) { return 0; }

        private OfficialRulebookImportJob copy(
                OfficialRulebookImportJob job,
                OfficialRulebookImportJob.Stage stage,
                long downloadedBytes,
                Long totalBytes,
                UUID documentVersionId,
                boolean duplicate,
                String errorCode,
                Instant updatedAt,
                Instant completedAt) {
            return new OfficialRulebookImportJob(
                    job.id(), job.ownerUsername(), job.editionId(), job.title(), job.sourceType(), job.sourceUrl(),
                    stage, downloadedBytes, totalBytes, documentVersionId, duplicate, errorCode,
                    job.createdAt(), updatedAt, completedAt);
        }
    }
}
