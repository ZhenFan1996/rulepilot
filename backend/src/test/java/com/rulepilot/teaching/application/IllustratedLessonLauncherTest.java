package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationOutcome;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationContinuation;
import com.rulepilot.teaching.application.GroundedTeachingAgent.BaseLessonContinuation;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

class IllustratedLessonLauncherTest {

    private final IllustratedLessonService lessons = mock(IllustratedLessonService.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void claimAcceptedRunsByDefault() {
        when(runs.activateQueued(any(), any(UUID.class), any(Instant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptsAndRunsNewGenerationOutsideTheRequestCall() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        assertThat(launch.reused()).isFalse();
        verify(lessons).startGeneration(planId, "alice", run);
        verify(lessons).continueGeneration(continuation);
        verify(lessons).finish(outcome);
    }

    @Test
    void immediatelyLaunchesFromThePreparedPlanWithoutReloadingIt() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(plan, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(plan, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launchImmediately(plan, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons).begin(plan, "alice");
        verify(lessons).startGeneration(plan, "alice", run);
        verify(lessons, never()).begin(planId, "alice");
        verify(lessons, never()).startGeneration(planId, "alice", run);
    }

    @Test
    void immediatelyClaimsAReceivedRunThatWasQueuedBehindPreparation() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(lessons.hasDurableCitedSection(planId)).thenReturn(false);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(plan, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launchImmediately(plan, "alice");

        assertThat(launch.reused()).isTrue();
        verify(lessons, never()).begin(plan, "alice");
        verify(lessons).startGeneration(plan, "alice", run);
    }

    @Test
    void keepsPreparationSuccessfulWhenAnotherWorkerAlreadyOwnsTheFirstSection() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(lessons.hasDurableCitedSection(planId)).thenReturn(false);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launchImmediately(plan, "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons, never()).begin(plan, "alice");
        verify(lessons, never()).startGeneration(plan, "alice", run);
    }

    @Test
    void reusesAnActiveRunOnlyAfterItsFirstCitedSectionIsDurable() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(lessons.hasDurableCitedSection(planId)).thenReturn(true);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launchImmediately(plan, "alice");

        assertThat(launch.reused()).isTrue();
        verify(lessons, never()).begin(plan, "alice");
        verify(lessons, never()).startGeneration(plan, "alice", run);
    }

    @Test
    void losingAReceivedRunClaimHandsOffToTheWinningWorkerWithoutFailingPreparation() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(lessons.hasDurableCitedSection(planId)).thenReturn(false);
        when(runs.activateQueued(org.mockito.ArgumentMatchers.eq(run), any(UUID.class), any(Instant.class)))
                .thenThrow(new AgentWorkAlreadyClaimedException());
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launchImmediately(plan, "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons, never()).begin(plan, "alice");
        verify(lessons, never()).startGeneration(plan, "alice", run);
    }

    @Test
    void retriesTheSameDurableClaimAfterAnAmbiguousCommitResponseAndGeneratesOnce() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        AtomicReference<UUID> firstToken = new AtomicReference<>();
        AtomicReference<Instant> firstAdmission = new AtomicReference<>();
        when(runs.activateQueued(
                        org.mockito.ArgumentMatchers.eq(run), any(UUID.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    UUID token = invocation.getArgument(1);
                    Instant admittedAt = invocation.getArgument(2);
                    if (firstToken.compareAndSet(null, token)) {
                        firstAdmission.set(admittedAt);
                        throw new IllegalStateException("claim committed but response was lost");
                    }
                    assertThat(token).isEqualTo(firstToken.get());
                    assertThat(admittedAt).isEqualTo(firstAdmission.get());
                    return run;
                });
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        launcher.launch(planId, "alice");

        verify(runs, times(2)).activateQueued(
                org.mockito.ArgumentMatchers.eq(run), eq(firstToken.get()), eq(firstAdmission.get()));
        verify(lessons).startGeneration(planId, "alice", run);
        verify(lessons).continueGeneration(continuation);
        verify(lessons).finish(outcome);
        verify(runs, never()).failQueuedIfUnactivatedOrOwned(
                eq(run.id()), eq("alice"), any(UUID.class), any(), any());
    }

    @Test
    void immediateLaunchCarriesThePersistedTeachingFailureCodeToPreparation() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        RunSnapshot failed = new RunSnapshot(
                run.id(),
                AssistantRunMode.TEACHING,
                planId,
                "alice",
                AssistantRunState.FAILED,
                1,
                run.createdAt(),
                run.updatedAt().plusSeconds(1),
                run.updatedAt().plusSeconds(1),
                "TEACHING_WORKFLOW_FAILED");
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(plan, "alice")).thenReturn(run);
        when(lessons.startGeneration(plan, "alice", run))
                .thenThrow(new IllegalStateException("provider contract failed"));
        when(runs.findOwned(run.id(), "alice")).thenReturn(Optional.of(details(failed)));
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        assertThatThrownBy(() -> launcher.launchImmediately(plan, "alice"))
                .isInstanceOfSatisfying(
                        IllustratedLessonLauncher.ImmediateLessonStartupFailure.class,
                        failure -> {
                            assertThat(failure.assistantRunId()).isEqualTo(run.id());
                            assertThat(failure.failureCode()).isEqualTo("TEACHING_WORKFLOW_FAILED");
                        });
    }

    @Test
    void reusesAnExistingNonTerminalRun() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.state()).isEqualTo(AssistantRunState.RETRIEVING);
        verify(lessons, never()).begin(planId, "alice");
        verify(lessons, never()).startGeneration(planId, "alice", run);
    }

    @Test
    void recordsAQueueFailureWhenBoundedExecutionRejectsTheTask() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("full");
        };
        var launcher = new IllustratedLessonLauncher(lessons, runs, rejectingExecutor);

        assertThatThrownBy(() -> launcher.launch(planId, "alice")).isInstanceOf(TaskRejectedException.class);
        verify(runs).failQueuedIfUnactivated(
                run.id(),
                "alice",
                "TEACHING_QUEUE_FULL",
                "Teaching generation could not enter its bounded worker queue");
    }

    @Test
    void recordsAQueueFailureForAnyExecutorFailureBeforeStartupBegins() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        TaskExecutor brokenExecutor = task -> {
            throw new IllegalStateException("executor unavailable");
        };
        var launcher = new IllustratedLessonLauncher(lessons, runs, brokenExecutor);

        assertThatThrownBy(() -> launcher.launch(planId, "alice")).isInstanceOf(IllegalStateException.class);
        verify(runs).failQueuedIfUnactivated(
                run.id(),
                "alice",
                "TEACHING_QUEUE_FULL",
                "Teaching generation could not enter its bounded worker queue");
    }

    @Test
    void expiresAcceptedTeachingThatNeverAcquiresAWorkerAndSuppressesItsLateRunnable() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        AtomicReference<Runnable> expiryCallback = new AtomicReference<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
        when(scheduler.schedule(
                        org.mockito.ArgumentMatchers.any(Runnable.class),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiryCallback.set(invocation.getArgument(0));
                    return expiry;
                });
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                queuedWorker::set,
                Runnable::run,
                scheduler,
                Duration.ofMinutes(2));

        launcher.launch(planId, "alice");
        expiryCallback.get().run();
        queuedWorker.get().run();

        verify(runs).failQueuedIfUnactivated(
                run.id(),
                "alice",
                "TEACHING_QUEUE_TIMEOUT",
                "Teaching generation waited too long for a worker and is safe to retry");
        verify(lessons, never()).startGeneration(planId, "alice", run);
    }

    @Test
    void anAlternateDurableClaimWinsAgainstTheOriginalInMemoryQueueExpiry() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        when(runs.failQueuedIfUnactivated(
                        run.id(),
                        "alice",
                        "TEACHING_QUEUE_TIMEOUT",
                        "Teaching generation waited too long for a worker and is safe to retry"))
                .thenReturn(false);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        AtomicReference<Runnable> expiryCallback = new AtomicReference<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
        when(scheduler.schedule(
                        org.mockito.ArgumentMatchers.any(Runnable.class),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiryCallback.set(invocation.getArgument(0));
                    return expiry;
                });
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                queuedWorker::set,
                Runnable::run,
                scheduler,
                Duration.ofMinutes(2));

        launcher.launch(planId, "alice");
        // Represents launchImmediately claiming the same durable RECEIVED run before this JVM queue expires.
        expiryCallback.get().run();
        queuedWorker.get().run();

        verify(runs).failQueuedIfUnactivated(
                run.id(),
                "alice",
                "TEACHING_QUEUE_TIMEOUT",
                "Teaching generation waited too long for a worker and is safe to retry");
        verify(runs, never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(lessons, never()).startGeneration(planId, "alice", run);
    }

    @Test
    void firstSectionStartupDoesNotWaitForAnOccupiedContinuationLane() throws InterruptedException {
        RunSnapshot firstRun = run(AssistantRunState.RECEIVED);
        RunSnapshot secondRun = run(AssistantRunState.RECEIVED);
        UUID secondPlanId = UUID.randomUUID();
        var firstContinuation = continuation(firstRun);
        var secondContinuation = continuation(secondRun);
        var releaseFirstContinuation = new CountDownLatch(1);
        var firstContinuationStarted = new CountDownLatch(1);
        var secondStartupCompleted = new CountDownLatch(1);
        TaskExecutor startup = task -> {
            task.run();
            secondStartupCompleted.countDown();
        };
        var continuationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        TaskExecutor continuationLane = continuationExecutor::execute;
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, secondPlanId, "bob")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(firstRun);
        when(lessons.begin(secondPlanId, "bob")).thenReturn(secondRun);
        when(lessons.startGeneration(planId, "alice", firstRun)).thenReturn(firstContinuation);
        when(lessons.startGeneration(secondPlanId, "bob", secondRun)).thenReturn(secondContinuation);
        when(lessons.continueGeneration(firstContinuation)).thenAnswer(ignored -> {
            firstContinuationStarted.countDown();
            releaseFirstContinuation.await(3, TimeUnit.SECONDS);
            return new GenerationOutcome(firstRun, LessonStatus.COMPLETE);
        });
        when(lessons.continueGeneration(secondContinuation))
                .thenReturn(new GenerationOutcome(secondRun, LessonStatus.COMPLETE));
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                startup,
                continuationLane);

        try {
            launcher.launch(planId, "alice");
            assertThat(firstContinuationStarted.await(1, TimeUnit.SECONDS)).isTrue();

            launcher.launch(secondPlanId, "bob");

            assertThat(secondStartupCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            verify(lessons).startGeneration(secondPlanId, "bob", secondRun);
        } finally {
            releaseFirstContinuation.countDown();
            continuationExecutor.shutdown();
            assertThat(continuationExecutor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preservesTheReadableFirstSectionWhenContinuationQueueRejectsTheLongTail() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var continuation = continuation(run);
        TaskExecutor rejectingContinuation = task -> {
            throw new TaskRejectedException("full");
        };
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                rejectingContinuation);

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons).startGeneration(planId, "alice", run);
        verify(runs).failActiveIfOwned(
                run.id(),
                "alice",
                "TEACHING_CONTINUATION_QUEUE_FULL",
                "The first cited section is readable but remaining teaching work could not enter its bounded worker queue");
        verify(lessons, never()).admitContinuation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(lessons, never()).failScheduling(run);
    }

    @Test
    void preservesTheReadableFirstSectionForAnyExecutorFailureBeforeContinuationBegins() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var continuation = continuation(run);
        TaskExecutor brokenContinuation = task -> {
            throw new IllegalStateException("executor unavailable");
        };
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                brokenContinuation);

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(runs).failActiveIfOwned(
                run.id(),
                "alice",
                "TEACHING_CONTINUATION_QUEUE_FULL",
                "The first cited section is readable but remaining teaching work could not enter its bounded worker queue");
        verify(lessons, never()).failScheduling(run);
    }

    @Test
    void expiresAContinuationThatNeverAcquiresAWorkerAndSuppressesItsLateRunnable() {
        RunSnapshot received = run(AssistantRunState.RECEIVED);
        RunSnapshot active = run(AssistantRunState.RETRIEVING);
        var continuation = continuation(active);
        AtomicReference<Runnable> queuedContinuation = new AtomicReference<>();
        List<Runnable> expiries = new java.util.ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
        when(scheduler.schedule(
                        org.mockito.ArgumentMatchers.any(Runnable.class),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiries.add(invocation.getArgument(0));
                    return expiry;
                });
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(received);
        when(lessons.startGeneration(planId, "alice", received)).thenReturn(continuation);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                queuedContinuation::set,
                scheduler,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30));

        launcher.launch(planId, "alice");
        assertThat(expiries).hasSize(2);
        expiries.get(1).run();
        queuedContinuation.get().run();

        verify(runs).failActiveIfOwned(
                active.id(),
                "alice",
                "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                "The first cited section is readable but remaining teaching work waited too long for a worker");
        verify(lessons, never()).admitContinuation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(lessons, never()).continueGeneration(continuation);
    }

    @Test
    void aMissingContinuationRunSettlesItsTerminalIntentWithoutRetryingForever() {
        RunSnapshot received = run(AssistantRunState.RECEIVED);
        RunSnapshot active = run(AssistantRunState.RETRIEVING);
        var continuation = continuation(active);
        AtomicReference<Runnable> queuedContinuation = new AtomicReference<>();
        List<Runnable> expiries = new java.util.ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        TeachingTerminalRecovery recovery = mock(TeachingTerminalRecovery.class);
        ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
        when(scheduler.schedule(
                        org.mockito.ArgumentMatchers.any(Runnable.class),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiries.add(invocation.getArgument(0));
                    return expiry;
                });
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(received);
        when(lessons.startGeneration(planId, "alice", received)).thenReturn(continuation);
        when(runs.failActiveIfOwned(
                        active.id(),
                        "alice",
                        "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                        "The first cited section is readable but remaining teaching work waited too long for a worker"))
                .thenReturn(false);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                queuedContinuation::set,
                scheduler,
                recovery,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30));

        launcher.launch(planId, "alice");
        expiries.get(1).run();

        verify(runs).failActiveIfOwned(
                active.id(),
                "alice",
                "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                "The first cited section is readable but remaining teaching work waited too long for a worker");
        verifyNoInteractions(recovery);
        verify(lessons, never()).admitContinuation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finishesAnEvidenceInsufficientLessonWithoutQueuingEmptyContinuationWork() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        BaseLessonContinuation base = mock(BaseLessonContinuation.class);
        var continuation = new GenerationContinuation(run, base);
        var outcome = new GenerationOutcome(run, LessonStatus.INCOMPLETE);
        AtomicReference<Runnable> queuedContinuation = new AtomicReference<>();
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                queuedContinuation::set);

        launcher.launch(planId, "alice");

        assertThat(queuedContinuation.get()).isNull();
        verify(lessons, never()).admitContinuation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(lessons).continueGeneration(continuation);
        verify(lessons).finish(outcome);
    }

    @Test
    void schedulesEachLongLessonChapterAsItsOwnRecoverableWorkUnit() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var continuation = continuation(run);
        var intermediate = new GenerationOutcome(run, LessonStatus.DRAFT_READY, continuation);
        var complete = new GenerationOutcome(run, LessonStatus.COMPLETE);
        ArrayDeque<Runnable> workUnits = new ArrayDeque<>();
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(intermediate, complete);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                Runnable::run,
                workUnits::addLast);

        launcher.launch(planId, "alice");

        assertThat(workUnits).hasSize(1);
        workUnits.removeFirst().run();
        verify(lessons, never()).finish(intermediate);
        assertThat(workUnits).hasSize(1);

        workUnits.removeFirst().run();
        verify(lessons, times(2)).admitContinuation(
                org.mockito.ArgumentMatchers.eq(continuation),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        verify(lessons, times(2)).continueGeneration(continuation);
        verify(lessons).finish(complete);
        assertThat(workUnits).isEmpty();
    }

    private RunSnapshot run(AssistantRunState state) {
        Instant now = Instant.parse("2026-07-20T10:00:00Z");
        return new RunSnapshot(
                UUID.randomUUID(), AssistantRunMode.TEACHING, planId, "alice", state, 0, now, now, null, null);
    }

    private RunDetails details(RunSnapshot run) {
        var budget = new AgentExecutionControl.BudgetSnapshot(
                24_000, 0, 0, 0, run.createdAt().plusSeconds(120), null);
        return new RunDetails(run, List.of(), budget, List.of());
    }

    private GenerationContinuation continuation(RunSnapshot run) {
        BaseLessonContinuation base = mock(BaseLessonContinuation.class);
        when(base.hasRemainingWork()).thenReturn(true);
        return new GenerationContinuation(run, base);
    }
}
