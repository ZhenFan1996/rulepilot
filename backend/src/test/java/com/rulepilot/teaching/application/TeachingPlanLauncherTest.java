package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineCapacityExceededException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.application.IllustratedLessonLauncher.ImmediateLessonStartupFailure;
import com.rulepilot.teaching.application.IllustratedLessonLauncher.LessonLaunch;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

class TeachingPlanLauncherTest {

    private static final WorkloadDemand PREPARATION_WORKLOAD = new WorkloadDemand(128);
    private final TeachingPlanService plans = mock(TeachingPlanService.class);
    private final IllustratedLessonLauncher lessons = mock(IllustratedLessonLauncher.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void acceptsPlanningThenStartsLessonGenerationOutsideTheRequest() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = launcher();

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(received.id());
        assertThat(launch.state()).isEqualTo(AssistantRunState.RECEIVED);
        assertThat(launch.reused()).isFalse();
        verify(runs).start(
                AssistantRunMode.TEACHING_PREPARATION,
                documentVersionId,
                "alice",
                PREPARATION_WORKLOAD);
        verify(runs).activateQueued(eq(received), any(UUID.class), any(Instant.class));
        verify(lessons).launchImmediately(plan, "alice");
        verify(runs).advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready");
        assertThat(metrics.find(TeachingPlanLauncher.STARTUP_PHASE_DURATION_METRIC)
                        .tag("phase", "plan-resolution")
                        .timer().count())
                .isEqualTo(1);
        assertThat(metrics.find(TeachingPlanLauncher.STARTUP_PHASE_DURATION_METRIC)
                        .tag("phase", "first-section-startup")
                        .timer().count())
                .isEqualTo(1);
    }

    @Test
    void reusesAnActivePreparationForTheSameRulebook() {
        RunSnapshot planning = run(AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.of(details(planning)));
        var launcher = launcher();

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.assistantRunId()).isEqualTo(planning.id());
        verify(runs, never()).start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice");
        verifyNoInteractions(plans);
    }

    @Test
    void duplicatePreparationDeliveryNeverFailsTheWorkerThatWonAdmission() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        var launcher = launcher();
        when(runs.activateQueued(eq(received), any(UUID.class), any(Instant.class)))
                .thenThrow(new AgentWorkAlreadyClaimedException());

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(received.id());
        verify(runs, never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString(),
                anyString());
        verify(plans, never()).create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyString(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(lessons);
    }

    @Test
    void retriesTheSameDurableClaimAfterAnAmbiguousCommitResponseAndRunsWorkOnce() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(UUID.randomUUID());
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = launcher();
        AtomicReference<UUID> firstToken = new AtomicReference<>();
        AtomicReference<Instant> firstAdmission = new AtomicReference<>();
        when(runs.activateQueued(eq(received), any(UUID.class), any(Instant.class))).thenAnswer(invocation -> {
            UUID token = invocation.getArgument(1);
            Instant admittedAt = invocation.getArgument(2);
            if (firstToken.compareAndSet(null, token)) {
                firstAdmission.set(admittedAt);
                throw new IllegalStateException("claim committed but response was lost");
            }
            assertThat(token).isEqualTo(firstToken.get());
            assertThat(admittedAt).isEqualTo(firstAdmission.get());
            return received;
        });

        launcher.launch(documentVersionId, "alice");

        verify(runs, times(2)).activateQueued(eq(received), eq(firstToken.get()), eq(firstAdmission.get()));
        verify(plans).create(documentVersionId, null, "alice", received.id());
        verify(lessons).launchImmediately(plan, "alice");
        verify(runs, never()).failQueuedIfUnactivatedOrOwned(
                eq(received.id()), eq("alice"), any(UUID.class), anyString(), anyString());
    }

    @Test
    void expiresLongPreparationThatNeverAcquiresAWorkerWithARetryableReason() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(
                        AssistantRunMode.TEACHING_PREPARATION,
                        documentVersionId,
                        "alice",
                        PREPARATION_WORKLOAD))
                .thenReturn(received);
        when(plans.preparationWorkload(documentVersionId, "alice")).thenReturn(PREPARATION_WORKLOAD);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        TaskExecutor longLane = queuedWorker::set;
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
        AtomicReference<Runnable> expiryCallback = new AtomicReference<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            expiryCallback.set(invocation.getArgument(0));
            return expiry;
        });
        var launcher = new TeachingPlanLauncher(
                plans,
                lessons,
                runs,
                new SyncTaskExecutor(),
                longLane,
                scheduler,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                metrics);

        launcher.launch(documentVersionId, "alice");
        expiryCallback.get().run();
        queuedWorker.get().run();

        verify(runs).failQueuedIfUnactivated(
                received.id(),
                "alice",
                "TEACHING_PREPARATION_QUEUE_TIMEOUT",
                "Teaching preparation waited too long for a worker and is safe to retry");
        verify(runs, never()).activateQueued(eq(received), any(UUID.class), any(Instant.class));
        verifyNoInteractions(lessons);
    }

    @Test
    void retriesRecordingAnExpiredAdmissionWithoutEverStartingTheQueuedWork() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(
                        AssistantRunMode.TEACHING_PREPARATION,
                        documentVersionId,
                        "alice",
                        PREPARATION_WORKLOAD))
                .thenReturn(received);
        when(plans.preparationWorkload(documentVersionId, "alice")).thenReturn(PREPARATION_WORKLOAD);
        when(runs.failQueuedIfUnactivated(
                        received.id(),
                        "alice",
                        "TEACHING_PREPARATION_QUEUE_TIMEOUT",
                        "Teaching preparation waited too long for a worker and is safe to retry"))
                .thenThrow(new IllegalStateException("temporary persistence outage"))
                .thenReturn(true);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        List<Runnable> scheduledCallbacks = new ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            scheduledCallbacks.add(invocation.getArgument(0));
            return scheduled;
        });
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                metrics,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        var launcher = new TeachingPlanLauncher(
                plans,
                lessons,
                runs,
                new SyncTaskExecutor(),
                queuedWorker::set,
                scheduler,
                recovery,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                metrics);

        launcher.launch(documentVersionId, "alice");
        scheduledCallbacks.get(0).run();
        assertThat(scheduledCallbacks).hasSize(2);
        scheduledCallbacks.get(1).run();
        queuedWorker.get().run();

        verify(runs, times(2)).failQueuedIfUnactivated(
                received.id(),
                "alice",
                "TEACHING_PREPARATION_QUEUE_TIMEOUT",
                "Teaching preparation waited too long for a worker and is safe to retry");
        verify(runs, never()).activateQueued(eq(received), any(UUID.class), any(Instant.class));
        verifyNoInteractions(lessons);
    }

    @Test
    void identifiesPlanResolutionAsTheEarliestOrdinaryRuntimeFailure() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new IllegalStateException("model unavailable"));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));
        var launcher = launcher();

        launcher.launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(),
                3,
                "TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED",
                "Teaching preparation failed safely");
        verify(lessons, never()).launch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesTheExactCancellationBoundaryDuringPlanResolution() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new IllegalStateException(
                        "planning cancelled",
                        new AgentExecutionStoppedException(StopReason.CANCELLED)));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "AGENT_CANCELLED", "Teaching preparation failed safely");
        verify(lessons, never()).launchImmediately(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void marksEvidenceStorageFailureForExternalRepairInsteadOfProviderRetry() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new IllegalStateException(
                        "planning stopped after persistence",
                        new TeachingPreparationStorageException(new IllegalStateException("storage unavailable"))));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_PREPARATION_STORAGE_FAILED", "Teaching preparation failed safely");
        verify(lessons, never()).launch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void identifiesFirstSectionStartupWithoutCompletingPreparation() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenThrow(new IllegalStateException("teaching queue unavailable"));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));
        var launcher = launcher();

        launcher.launch(documentVersionId, "alice");

        verify(runs, never()).advance(
                eq(received.id()), eq(3L), eq(AssistantRunState.COMPLETED), anyString());
        verify(runs).fail(
                received.id(),
                3,
                "TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED",
                "Teaching preparation failed safely");
    }

    @Test
    void preservesTheCausalTeachingFailureInsteadOfAuthorizingAPhaseRetry() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID teachingRunId = UUID.randomUUID();
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenThrow(new ImmediateLessonStartupFailure(
                        teachingRunId,
                        "TEACHING_WORKFLOW_FAILED",
                        new IllegalArgumentException("generated lesson violated its contract")));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_WORKFLOW_FAILED", "Teaching preparation failed safely");
        verify(runs, never()).advance(
                eq(received.id()), eq(3L), eq(AssistantRunState.COMPLETED), anyString());
    }

    @Test
    void keepsAFirstSectionStorageFailureAtTheExistingHardBoundary() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenThrow(new IllegalStateException(
                        "first section stopped after persistence",
                        new TeachingPreparationStorageException(new IllegalStateException("storage unavailable"))));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_PREPARATION_STORAGE_FAILED", "Teaching preparation failed safely");
        verify(runs, never()).advance(
                eq(received.id()), eq(3L), eq(AssistantRunState.COMPLETED), anyString());
    }

    @Test
    void reusesACompatiblePlanWhenRetryingLessonGeneration() {
        String learningGoal = "先让我能带大家开局，再重点讲行动衔接。";
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan existingPlan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        when(existingPlan.id()).thenReturn(planId);
        when(existingPlan.learningGoal()).thenReturn(learningGoal);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.latest(documentVersionId, "alice")).thenReturn(Optional.of(existingPlan));
        when(lessons.launchImmediately(existingPlan, "alice"))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = launcher();

        launcher.launch(documentVersionId, learningGoal, "alice");

        verify(plans, never()).create(
                documentVersionId, learningGoal, "alice", received.id());
        verify(plans).refreshVisualEvidence(documentVersionId, "alice", received.id());
        verify(lessons).launchImmediately(existingPlan, "alice");
        verify(runs).advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready");
        var ordered = inOrder(plans, lessons);
        ordered.verify(plans).latest(documentVersionId, "alice");
        ordered.verify(plans).refreshVisualEvidence(documentVersionId, "alice", received.id());
        ordered.verify(lessons).launchImmediately(existingPlan, "alice");
    }

    @Test
    void doesNotCatalogVisualEvidenceTwiceWhileCreatingANewPlan() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.latest(documentVersionId, "alice")).thenReturn(Optional.empty());
        when(plans.create(documentVersionId, null, "alice", received.id())).thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = launcher();

        launcher.launch(documentVersionId, "alice");

        verify(plans, never()).refreshVisualEvidence(documentVersionId, "alice", received.id());
        verify(plans).create(documentVersionId, null, "alice", received.id());
    }

    @Test
    void labelsAnInvalidModelPlanSoTheClientCanOfferASpecificRetry() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new IllegalArgumentException("outline omitted scoring"));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));
        var launcher = launcher();

        launcher.launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_PREPARATION_INVALID_PLAN", "Teaching preparation failed safely");
    }

    @Test
    void labelsAnOutlineCapacityBoundaryWithoutRetryingAnImpossiblePlan() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new OutlineGenerationException(
                        "teaching outline generation returned no valid outline",
                        new OutlineCapacityExceededException(
                                "canonical teaching units exceed the bounded global ordering context")));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_OUTLINE_CAPACITY_EXCEEDED", "Teaching preparation failed safely");
    }

    @Test
    void keepsADeeplyWrappedSourceContractFailureDeterministic() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new IllegalStateException(
                        "source-bound outline was incomplete",
                        new IllegalArgumentException("one source unit has no owner")));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));

        launcher().launch(documentVersionId, "alice");

        verify(runs).fail(
                received.id(), 3, "TEACHING_PREPARATION_INVALID_PLAN", "Teaching preparation failed safely");
    }

    @Test
    void recordsAFailureBeforeRethrowingAFatalBackgroundWorkerError() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(documentVersionId, null, "alice", received.id()))
                .thenThrow(new AssertionError("simulated worker fault"));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));
        var launcher = launcher();

        assertThatThrownBy(() -> launcher.launch(documentVersionId, "alice"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("simulated worker fault");

        verify(runs).fail(
                received.id(), 3, "TEACHING_PREPARATION_FAILED", "Teaching preparation failed safely");
        verify(lessons, never()).launch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void carriesTheNaturalLearningGoalIntoBackgroundPlanning() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(UUID.randomUUID());
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.empty());
        when(runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(received);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rulebook pages are ready for teaching"))
                .thenReturn(ready);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Reading rulebook pages and organizing the lesson"))
                .thenReturn(planning);
        when(plans.create(
                        documentVersionId,
                        "先让我能带大家开局，再重点讲行动衔接。",
                        "alice",
                        received.id()))
                .thenReturn(plan);
        when(lessons.launchImmediately(plan, "alice"))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = launcher();

        launcher.launch(
                documentVersionId,
                "先让我能带大家开局，再重点讲行动衔接。",
                "alice");

        verify(plans).create(
                documentVersionId,
                "先让我能带大家开局，再重点讲行动衔接。",
                "alice",
                received.id());
    }

    private RunSnapshot run(AssistantRunState state, long revision) {
        return run(UUID.randomUUID(), state, revision);
    }

    private TeachingPlanLauncher launcher() {
        when(plans.preparationWorkload(documentVersionId, "alice"))
                .thenReturn(PREPARATION_WORKLOAD);
        when(runs.start(
                        AssistantRunMode.TEACHING_PREPARATION,
                        documentVersionId,
                        "alice",
                        PREPARATION_WORKLOAD))
                .thenAnswer(ignored -> runs.start(
                        AssistantRunMode.TEACHING_PREPARATION,
                        documentVersionId,
                        "alice"));
        when(runs.activateQueued(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(UUID.class),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor(), metrics);
    }

    private RunSnapshot run(UUID id, AssistantRunState state, long revision) {
        Instant now = Instant.parse("2026-07-22T03:00:00Z").plusSeconds(revision);
        return new RunSnapshot(
                id,
                AssistantRunMode.TEACHING_PREPARATION,
                documentVersionId,
                "alice",
                state,
                revision,
                now.minusSeconds(revision),
                now,
                state.terminal() ? now : null,
                null);
    }

    private RunDetails details(RunSnapshot run) {
        var budget = new AgentExecutionControl.BudgetSnapshot(
                160_000, 0, 0, 0, run.createdAt().plusSeconds(600), null);
        return new RunDetails(run, List.of(), budget, List.of());
    }
}
