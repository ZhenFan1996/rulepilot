package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.FailedTeachingHandoffRemovals.HandoffState;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Origin;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailedTeachingHandoffRemovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Mock OfficialRulebookImportJobRepository officialImports;
    @Mock UploadedRulebookTeachingHandoffStore uploads;
    @Mock RuleDocumentRepository documents;

    @Test
    void exposesTheExactLaunchedRunForTerminalityValidationBeforeDismissal() {
        UUID jobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(officialImports.findOwned(jobId, "alice"))
                .thenReturn(Optional.of(officialJob(jobId, versionId, runId)));

        var candidate = service().findOwned(Origin.OFFICIAL_IMPORT, jobId, " alice ").orElseThrow();

        assertThat(candidate.origin()).isEqualTo(Origin.OFFICIAL_IMPORT);
        assertThat(candidate.documentVersionId()).isEqualTo(versionId);
        assertThat(candidate.preparationRunId()).isEqualTo(runId);
        assertThat(candidate.handoffState()).isEqualTo(HandoffState.LAUNCHED);
        assertThat(candidate.failureRecordedWithoutRun()).isFalse();
    }

    @Test
    void deletesAnOwnedFailedUploadRecordButNotItsDocument() {
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var snapshot = new UploadedRulebookTeachingHandoffStore.Snapshot(
                handoffId,
                versionId,
                "alice",
                null,
                UploadedRulebookTeachingHandoffStore.State.FAILED,
                null,
                "TEACHING_HANDOFF_LAUNCH_FAILED",
                NOW.minusSeconds(60),
                NOW);
        when(uploads.findOwned(handoffId, "alice")).thenReturn(Optional.of(snapshot));
        when(uploads.dismissOwned(
                        handoffId,
                        "alice",
                        UploadedRulebookTeachingHandoffStore.State.FAILED,
                        null))
                .thenReturn(true);
        var service = service();
        var candidate = service.findOwned(Origin.UPLOAD, handoffId, "alice").orElseThrow();

        assertThat(service.dismissOwned(candidate, "alice")).isTrue();

        verify(uploads).dismissOwned(
                handoffId,
                "alice",
                UploadedRulebookTeachingHandoffStore.State.FAILED,
                null);
    }

    @Test
    void doesNotOfferAnOrdinaryWaitingUploadAsAFailedTask() {
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var snapshot = new UploadedRulebookTeachingHandoffStore.Snapshot(
                handoffId,
                versionId,
                "alice",
                null,
                UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT,
                null,
                null,
                NOW.minusSeconds(60),
                NOW);
        when(uploads.findOwned(handoffId, "alice")).thenReturn(Optional.of(snapshot));
        when(documents.findVersion(versionId)).thenReturn(Optional.empty());

        assertThat(service().findOwned(Origin.UPLOAD, handoffId, "alice")).isEmpty();
    }

    private FailedTeachingHandoffRemovalService service() {
        return new FailedTeachingHandoffRemovalService(
                officialImports,
                uploads,
                documents,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OfficialRulebookImportJob officialJob(UUID jobId, UUID versionId, UUID runId) {
        return new OfficialRulebookImportJob(
                jobId,
                "alice",
                UUID.randomUUID(),
                "Rulebook",
                DocumentSourceType.BASE_RULEBOOK,
                "https://example.test/rules.pdf",
                OfficialRulebookImportJob.Stage.COMPLETED,
                1024,
                1024L,
                versionId,
                false,
                null,
                new OfficialRulebookImportJob.TeachingHandoff(
                        OfficialRulebookImportJob.TeachingHandoffState.LAUNCHED,
                        null,
                        runId,
                        null,
                        NOW),
                NOW.minusSeconds(120),
                NOW,
                NOW);
    }
}
