package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.document.UploadedRulebookTeachingHandoffs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void repeatedReadyWakeupsCannotLaunchTheSamePersistedIntentTwice() throws InterruptedException {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        handoffs.ready.add(new UploadedRulebookTeachingHandoffs.ReadyHandoff(
                handoffId, versionId, "alice", null));
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(versionId, null, "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);
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
        assertThat(handoffs.launched).containsExactly(new LaunchRecord(handoffId, runId));
        verify(plans).launch(versionId, null, "alice");
    }

    @Test
    void readyWakeupClaimsOnlyTheMatchingDocumentIntent() {
        FakeHandoffs handoffs = new FakeHandoffs();
        UUID matchingHandoffId = UUID.randomUUID();
        UUID matchingVersionId = UUID.randomUUID();
        UUID otherVersionId = UUID.randomUUID();
        handoffs.ready.add(new UploadedRulebookTeachingHandoffs.ReadyHandoff(
                matchingHandoffId, matchingVersionId, "alice", null));
        handoffs.ready.add(new UploadedRulebookTeachingHandoffs.ReadyHandoff(
                UUID.randomUUID(), otherVersionId, "bob", null));
        UUID runId = UUID.randomUUID();
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        when(plans.launch(matchingVersionId, null, "alice"))
                .thenReturn(new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false));
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.dispatchReadyHandoffs(matchingVersionId);

        assertThat(handoffs.launched).containsExactly(new LaunchRecord(matchingHandoffId, runId));
        assertThat(handoffs.ready)
                .extracting(UploadedRulebookTeachingHandoffs.ReadyHandoff::documentVersionId)
                .containsExactly(otherVersionId);
    }

    @Test
    void repeatedBrokerDeliveryCannotLaunchAnAlreadyClaimedIntentAgain() {
        UploadedRulebookTeachingHandoffs handoffs = mock(UploadedRulebookTeachingHandoffs.class);
        TeachingPlanLauncher plans = mock(TeachingPlanLauncher.class);
        UUID versionId = UUID.randomUUID();
        when(handoffs.claimReadyForDocument(versionId, 4)).thenReturn(List.of());
        var launcher = new UploadedRulebookTeachingLauncher(handoffs, plans, 4);

        launcher.dispatchReadyHandoffs(versionId);
        launcher.dispatchReadyHandoffs(versionId);

        verify(handoffs, org.mockito.Mockito.times(2)).claimReadyForDocument(versionId, 4);
        verifyNoInteractions(plans);
    }

    private static final class FakeHandoffs implements UploadedRulebookTeachingHandoffs {
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
