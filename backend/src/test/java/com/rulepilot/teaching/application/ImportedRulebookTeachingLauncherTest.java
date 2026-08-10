package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.document.RulebookTeachingHandoffs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportedRulebookTeachingLauncherTest {

    @Test
    void launchesThePersistedReadyHandoffAndRecordsTheRealPreparationRun() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID jobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        handoffs.ready.add(new RulebookTeachingHandoffs.ReadyHandoff(
                jobId, versionId, "alice", "重点讲清开局和第一轮。"));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, "重点讲清开局和第一轮。", "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.launchReadyHandoffs();

        assertThat(handoffs.launched).containsExactly(new LaunchRecord(jobId, runId));
        assertThat(handoffs.failed).isEmpty();
    }

    @Test
    void turnsASchedulingFailureIntoAPersistedRecoverableHandoffFailure() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID jobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        handoffs.ready.add(new RulebookTeachingHandoffs.ReadyHandoff(jobId, versionId, "alice", null));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, null, "alice")).thenThrow(new IllegalStateException("executor full"));
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.launchReadyHandoffs();

        assertThat(handoffs.launched).isEmpty();
        assertThat(handoffs.failed).containsExactly(
                new FailureRecord(jobId, "TEACHING_HANDOFF_LAUNCH_FAILED"));
    }

    @Test
    void marksAnInterruptedClaimBeforeDrainingRecoverableWaitingWork() {
        RulebookTeachingHandoffs handoffs = mock(RulebookTeachingHandoffs.class);
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(handoffs.claimReady(4)).thenReturn(List.of());
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.recoverAndLaunch();

        verify(handoffs).failInterruptedLaunches();
        verify(handoffs).claimReady(4);
    }

    private static final class FakeHandoffs implements RulebookTeachingHandoffs {
        private final List<ReadyHandoff> ready = new ArrayList<>();
        private final List<LaunchRecord> launched = new ArrayList<>();
        private final List<FailureRecord> failed = new ArrayList<>();

        @Override
        public List<ReadyHandoff> claimReady(int limit) {
            List<ReadyHandoff> claimed = ready.stream().limit(limit).toList();
            ready.removeAll(claimed);
            return claimed;
        }

        @Override
        public void markLaunched(UUID importJobId, UUID preparationRunId) {
            launched.add(new LaunchRecord(importJobId, preparationRunId));
        }

        @Override
        public void markFailed(UUID importJobId, String errorCode) {
            failed.add(new FailureRecord(importJobId, errorCode));
        }

        @Override
        public int failInterruptedLaunches() {
            return 0;
        }
    }

    private record LaunchRecord(UUID jobId, UUID runId) {}

    private record FailureRecord(UUID jobId, String errorCode) {}
}
