package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoff;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoffState;
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
            progress.downloadCompleted();
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
        assertThat(completed.downloadCompletedAt()).isEqualTo(NOW);
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
    void persistsTheAutomaticTeachingIntentBeforeStartingTheDownloadWorker() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.job().teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(launch.job().teachingHandoff().learningGoal()).isEqualTo("重点讲清开局和第一轮。");
        verify(executor).execute(any());
    }

    @Test
    void reusesACompletedBoundImportAndUpgradesItToAutomaticTeaching() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        var completed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", editionId, "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(completed);
        jobs.complete(completed.id(), UUID.randomUUID(), false, NOW);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.job().id()).isEqualTo(completed.id());
        assertThat(launch.job().teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        verifyNoInteractions(executor, imports);
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
            progress.downloadCompleted();
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

    @Test
    void retriesTheExistingDurableTeachingHandoffWithoutDownloadingTheRulebookAgain() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), failedRunId, NOW);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        var service = service(jobs, imports, executor);

        var retried = service.retryTeaching(job.id(), failedRunId, "alice");

        assertThat(retried.documentVersionId()).isEqualTo(versionId);
        assertThat(retried.teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(retried.teachingHandoff().preparationRunId()).isNull();
        verifyNoInteractions(executor, imports);
    }

    @Test
    void aStaleDuplicateRetryCannotReplaceTheNewerPreparationRun() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        UUID oldRunId = UUID.randomUUID();
        UUID newerRunId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), oldRunId, NOW);
        var service = service(jobs, mock(OfficialRulebookImportService.class), mock(TaskExecutor.class));
        service.retryTeaching(job.id(), oldRunId, "alice");
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), newerRunId, NOW);

        var unchanged = service.retryTeaching(job.id(), oldRunId, "alice");

        assertThat(unchanged.teachingHandoff().state()).isEqualTo(TeachingHandoffState.LAUNCHED);
        assertThat(unchanged.teachingHandoff().preparationRunId()).isEqualTo(newerRunId);
    }

    @Test
    void doesNotRetryTeachingWhenThePersistedRulebookProcessingFailed() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.failTeachingLaunch(job.id(), "DOCUMENT_PROCESSING_FAILED", NOW);
        var service = service(jobs, mock(OfficialRulebookImportService.class), mock(TaskExecutor.class));

        assertThatThrownBy(() -> service.retryTeaching(job.id(), null, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rulebook processing failed");
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

    private OfficialRulebookImportJobService.Command automaticTeachingCommand() {
        return new OfficialRulebookImportJobService.Command(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                "重点讲清开局和第一轮。");
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
        public Optional<OfficialRulebookImportJob> findCompletedOwnedBySourceAndEdition(
                String ownerUsername, String sourceUrl, UUID editionId) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl))
                    .filter(job -> java.util.Objects.equals(job.editionId(), editionId))
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED
                            && job.documentVersionId() != null)
                    .findFirst();
        }

        @Override
        public List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit) {
            return values.values().stream().filter(job -> job.ownerUsername().equals(ownerUsername)).limit(limit).toList();
        }

        @Override
        public void requestTeaching(UUID jobId, String learningGoal, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    TeachingHandoff.requested(learningGoal, now), now, job.completedAt()));
        }

        @Override
        public boolean retryTeaching(UUID jobId, UUID expectedPreparationRunId, Instant now) {
            var job = values.get(jobId);
            boolean eligible = job.teachingHandoff().state() == TeachingHandoffState.FAILED
                    || job.teachingHandoff().state() == TeachingHandoffState.LAUNCHED
                            && java.util.Objects.equals(
                                    job.teachingHandoff().preparationRunId(), expectedPreparationRunId);
            if (!eligible) return false;
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    TeachingHandoff.requested(job.teachingHandoff().learningGoal(), now),
                    now, job.completedAt()));
            return true;
        }

        @Override
        public List<OfficialRulebookImportJob> claimReadyTeaching(int limit, Instant now) {
            return claimReadyTeaching(null, limit, now);
        }

        @Override
        public List<OfficialRulebookImportJob> claimReadyTeachingForDocument(
                UUID documentVersionId, int limit, Instant now) {
            return claimReadyTeaching(documentVersionId, limit, now);
        }

        private List<OfficialRulebookImportJob> claimReadyTeaching(
                UUID documentVersionId, int limit, Instant now) {
            List<OfficialRulebookImportJob> claimed = values.values().stream()
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED)
                    .filter(job -> job.teachingHandoff().state() == TeachingHandoffState.WAITING_FOR_DOCUMENT)
                    .filter(job -> documentVersionId == null || documentVersionId.equals(job.documentVersionId()))
                    .limit(limit)
                    .toList();
            for (var job : claimed) {
                var launching = new TeachingHandoff(
                        TeachingHandoffState.LAUNCHING,
                        job.teachingHandoff().learningGoal(),
                        null,
                        null,
                        now);
                values.put(job.id(), copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                        job.documentVersionId(), job.duplicate(), job.errorCode(), launching, now, job.completedAt()));
            }
            return claimed.stream().map(job -> values.get(job.id())).toList();
        }

        @Override public int failTeachingForUnusableDocuments(Instant now) { return 0; }

        @Override
        public void completeTeachingLaunch(UUID jobId, UUID preparationRunId, Instant now) {
            var job = values.get(jobId);
            var launched = new TeachingHandoff(
                    TeachingHandoffState.LAUNCHED,
                    job.teachingHandoff().learningGoal(),
                    preparationRunId,
                    null,
                    now);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(), launched, now, job.completedAt()));
        }

        @Override
        public void failTeachingLaunch(UUID jobId, String errorCode, Instant now) {
            var job = values.get(jobId);
            var failed = new TeachingHandoff(
                    TeachingHandoffState.FAILED,
                    job.teachingHandoff().learningGoal(),
                    null,
                    errorCode,
                    now);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(), failed, now, job.completedAt()));
        }

        @Override public int failInterruptedTeachingLaunches(Instant now) { return 0; }

        @Override
        public void markDownloadCompleted(UUID jobId, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, new OfficialRulebookImportJob(
                    job.id(), job.ownerUsername(), job.editionId(), job.title(), job.sourceType(), job.sourceUrl(),
                    job.stage(), job.downloadedBytes(), job.totalBytes(), job.documentVersionId(), job.duplicate(),
                    job.errorCode(), now, job.teachingHandoff(), job.createdAt(), now, job.completedAt()));
        }

        @Override
        public void updateProgress(
                UUID jobId, OfficialRulebookImportJob.Stage stage, long downloadedBytes, Long totalBytes, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, stage, downloadedBytes, totalBytes, null, false, null,
                    job.teachingHandoff(), now, null));
            stages.add(stage);
        }

        @Override
        public void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.COMPLETED,
                    job.downloadedBytes(), job.totalBytes(), documentVersionId, duplicate, null,
                    job.teachingHandoff(), now, now));
            stages.add(OfficialRulebookImportJob.Stage.COMPLETED);
        }

        @Override
        public void fail(UUID jobId, String errorCode, Instant now) {
            var job = values.get(jobId);
            TeachingHandoff handoff = job.teachingHandoff().state() == TeachingHandoffState.NOT_REQUESTED
                    ? job.teachingHandoff()
                    : new TeachingHandoff(
                            TeachingHandoffState.FAILED,
                            job.teachingHandoff().learningGoal(),
                            null,
                            "IMPORT_FAILED",
                            now);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.FAILED,
                    job.downloadedBytes(), job.totalBytes(), null, false, errorCode, handoff, now, now));
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
                TeachingHandoff teachingHandoff,
                Instant updatedAt,
                Instant completedAt) {
            return new OfficialRulebookImportJob(
                    job.id(), job.ownerUsername(), job.editionId(), job.title(), job.sourceType(), job.sourceUrl(),
                    stage, downloadedBytes, totalBytes, documentVersionId, duplicate, errorCode,
                    job.downloadCompletedAt(), teachingHandoff, job.createdAt(), updatedAt, completedAt);
        }
    }
}
