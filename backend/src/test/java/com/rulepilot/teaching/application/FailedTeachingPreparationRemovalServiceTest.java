package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.document.FailedTeachingHandoffRemovals;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Candidate;
import com.rulepilot.document.FailedTeachingHandoffRemovals.HandoffState;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Origin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailedTeachingPreparationRemovalServiceTest {

    @Mock FailedTeachingHandoffRemovals handoffs;
    @Mock AssistantRuns runs;

    @Test
    void removesARecordedLaunchFailureWithoutRequiringAnAssistantRun() {
        UUID sourceId = UUID.randomUUID();
        Candidate candidate = new Candidate(
                Origin.UPLOAD, sourceId, UUID.randomUUID(), null, HandoffState.FAILED, true);
        when(handoffs.findOwned(Origin.UPLOAD, sourceId, "alice")).thenReturn(Optional.of(candidate));
        when(handoffs.dismissOwned(candidate, "alice")).thenReturn(true);

        new FailedTeachingPreparationRemovalService(handoffs, runs).removeUpload(sourceId, "alice");

        verify(handoffs).dismissOwned(candidate, "alice");
        verify(runs, never()).findOwned(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removesAReferencedRunOnlyAfterItReachedAFailureState() {
        UUID sourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Candidate candidate = new Candidate(
                Origin.OFFICIAL_IMPORT, sourceId, versionId, runId, HandoffState.LAUNCHED, false);
        when(handoffs.findOwned(Origin.OFFICIAL_IMPORT, sourceId, "alice"))
                .thenReturn(Optional.of(candidate));
        when(runs.findOwned(runId, "alice")).thenReturn(Optional.of(details(
                runId, versionId, AssistantRunState.FAILED)));
        when(handoffs.dismissOwned(candidate, "alice")).thenReturn(true);

        new FailedTeachingPreparationRemovalService(handoffs, runs)
                .removeOfficialImport(sourceId, "alice");

        verify(handoffs).dismissOwned(candidate, "alice");
    }

    @Test
    void refusesToDismissAStillRunningPreparation() {
        UUID sourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Candidate candidate = new Candidate(
                Origin.UPLOAD, sourceId, versionId, runId, HandoffState.LAUNCHED, false);
        when(handoffs.findOwned(Origin.UPLOAD, sourceId, "alice")).thenReturn(Optional.of(candidate));
        when(runs.findOwned(runId, "alice")).thenReturn(Optional.of(details(
                runId, versionId, AssistantRunState.LESSON_PLANNING)));

        var service = new FailedTeachingPreparationRemovalService(handoffs, runs);

        assertThatThrownBy(() -> service.removeUpload(sourceId, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a removable failure");
        verify(handoffs, never()).dismissOwned(candidate, "alice");
    }

    @Test
    void refusesToDismissACompletedPreparation() {
        UUID sourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Candidate candidate = new Candidate(
                Origin.UPLOAD, sourceId, versionId, runId, HandoffState.LAUNCHED, false);
        when(handoffs.findOwned(Origin.UPLOAD, sourceId, "alice")).thenReturn(Optional.of(candidate));
        when(runs.findOwned(runId, "alice")).thenReturn(Optional.of(details(
                runId, versionId, AssistantRunState.COMPLETED)));

        var service = new FailedTeachingPreparationRemovalService(handoffs, runs);

        assertThatThrownBy(() -> service.removeUpload(sourceId, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a removable failure");
        verify(handoffs, never()).dismissOwned(candidate, "alice");
    }

    private AssistantRuns.RunDetails details(UUID runId, UUID versionId, AssistantRunState state) {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        var snapshot = new AssistantRuns.RunSnapshot(
                runId,
                AssistantRunMode.TEACHING_PREPARATION,
                versionId,
                "alice",
                state,
                1,
                now,
                now,
                state.terminal() ? now : null,
                state == AssistantRunState.FAILED ? "TEACHING_PREPARATION_FAILED" : null);
        var budget = new AgentExecutionControl.BudgetSnapshot(
                40, 24, 16, 24_000, 0, 0, 0, now.plusSeconds(60), null);
        return new AssistantRuns.RunDetails(snapshot, List.of(), budget, List.of());
    }
}
