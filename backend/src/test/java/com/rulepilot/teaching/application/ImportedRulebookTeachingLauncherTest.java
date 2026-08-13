package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.document.RulebookTeachingHandoffs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        verify(handoffs).failUnusableDocuments();
        verify(handoffs).claimReady(4);
    }

    @Test
    void repeatedReadyWakeupsCannotLaunchTheSamePersistedIntentTwice() throws InterruptedException {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID jobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        handoffs.ready.add(new RulebookTeachingHandoffs.ReadyHandoff(jobId, versionId, "alice", null));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, null, "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);
        var start = new CountDownLatch(1);
        var completed = new CountDownLatch(2);

        Runnable wakeup = () -> {
            try {
                start.await(1, TimeUnit.SECONDS);
                launcher.dispatchReadyHandoffs(versionId);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        };
        Thread first = Thread.ofPlatform().start(wakeup);
        Thread second = Thread.ofPlatform().start(wakeup);
        start.countDown();

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        first.join();
        second.join();
        assertThat(handoffs.launched).containsExactly(new LaunchRecord(jobId, runId));
        verify(plans).launch(versionId, null, "alice");
    }

    @Test
    void readyWakeupClaimsOnlyTheMatchingDocumentIntent() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID matchingJobId = UUID.randomUUID();
        UUID matchingVersionId = UUID.randomUUID();
        UUID otherVersionId = UUID.randomUUID();
        handoffs.ready.add(new RulebookTeachingHandoffs.ReadyHandoff(
                matchingJobId, matchingVersionId, "alice", null));
        handoffs.ready.add(new RulebookTeachingHandoffs.ReadyHandoff(
                UUID.randomUUID(), otherVersionId, "bob", null));
        UUID runId = UUID.randomUUID();
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(matchingVersionId, null, "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.dispatchReadyHandoffs(matchingVersionId);

        assertThat(handoffs.launched).containsExactly(new LaunchRecord(matchingJobId, runId));
        assertThat(handoffs.ready)
                .extracting(RulebookTeachingHandoffs.ReadyHandoff::documentVersionId)
                .containsExactly(otherVersionId);
    }

    @Test
    void repeatedBrokerDeliveryCannotLaunchAnAlreadyClaimedIntentAgain() {
        RulebookTeachingHandoffs handoffs = mock(RulebookTeachingHandoffs.class);
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        UUID versionId = UUID.randomUUID();
        when(handoffs.claimReadyForDocument(versionId, 4)).thenReturn(List.of());
        var launcher = new ImportedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.dispatchReadyHandoffs(versionId);
        launcher.dispatchReadyHandoffs(versionId);

        verify(handoffs, org.mockito.Mockito.times(2)).claimReadyForDocument(versionId, 4);
        verifyNoInteractions(plans);
    }

    private static final class FakeHandoffs implements RulebookTeachingHandoffs {
        private final List<ReadyHandoff> ready = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<LaunchRecord> launched = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<FailureRecord> failed = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized List<ReadyHandoff> claimReady(int limit) {
            return claim(null, limit);
        }

        @Override
        public synchronized List<ReadyHandoff> claimReadyForDocument(UUID documentVersionId, int limit) {
            return claim(documentVersionId, limit);
        }

        private List<ReadyHandoff> claim(UUID documentVersionId, int limit) {
            List<ReadyHandoff> claimed = ready.stream()
                    .filter(handoff -> documentVersionId == null || handoff.documentVersionId().equals(documentVersionId))
                    .limit(limit)
                    .toList();
            ready.removeAll(claimed);
            return claimed;
        }

        @Override
        public int failUnusableDocuments() {
            return 0;
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
