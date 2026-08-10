package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.document.UploadedRulebookTeachingHandoffs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadedRulebookTeachingLauncherTest {

    @Test
    void launchesPersistedPlayerUploadAfterTheDocumentBecomesReady() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        handoffs.ready.add(new UploadedRulebookTeachingHandoffs.ReadyHandoff(
                handoffId, versionId, "alice", "先讲清开局。"));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, "先讲清开局。", "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.launchReadyHandoffs();

        assertThat(handoffs.launched).containsExactly(new LaunchRecord(handoffId, runId));
        assertThat(handoffs.failed).isEmpty();
    }

    @Test
    void persistsASchedulingFailureForThePlayerToRetry() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        handoffs.ready.add(new UploadedRulebookTeachingHandoffs.ReadyHandoff(
                handoffId, versionId, "alice", null));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, null, "alice")).thenThrow(new IllegalStateException("executor full"));
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.launchReadyHandoffs();

        assertThat(handoffs.launched).isEmpty();
        assertThat(handoffs.failed).containsExactly(
                new FailureRecord(handoffId, "TEACHING_HANDOFF_LAUNCH_FAILED"));
    }

    @Test
    void marksInterruptedClaimsBeforeDrainingReadyUploads() {
        UploadedRulebookTeachingHandoffs handoffs = mock(UploadedRulebookTeachingHandoffs.class);
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(handoffs.claimReady(4)).thenReturn(List.of());
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.recoverAndLaunch();

        verify(handoffs).failInterruptedLaunches();
        verify(handoffs).failUnusableDocuments();
        verify(handoffs).claimReady(4);
    }

    private static final class FakeHandoffs implements UploadedRulebookTeachingHandoffs {
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
        public int failUnusableDocuments() {
            return 0;
        }

        @Override
        public void markLaunched(UUID handoffId, UUID preparationRunId) {
            launched.add(new LaunchRecord(handoffId, preparationRunId));
        }

        @Override
        public void markFailed(UUID handoffId, String errorCode) {
            failed.add(new FailureRecord(handoffId, errorCode));
        }

        @Override
        public int failInterruptedLaunches() {
            return 0;
        }
    }

    private record LaunchRecord(UUID handoffId, UUID runId) {}

    private record FailureRecord(UUID handoffId, String errorCode) {}
}
