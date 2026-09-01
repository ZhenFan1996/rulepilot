package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.assistant.AgentExecutionControl;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.TaskExecutor;

class IllustratedLessonLauncherTest {

    private final IllustratedLessonService lessons = mock(IllustratedLessonService.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final UUID planId = UUID.randomUUID();

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
    void bindsEveryLessonIdentityBeforeDispatchAndRecoversTheWorkerTraceByTeachingRunOwner() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        BaseLessonContinuation base = mock(BaseLessonContinuation.class);
        when(base.hasRemainingWork()).thenReturn(false);
        var continuation = new GenerationContinuation(run, base);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        RecordingCapture requestCapture = new RecordingCapture();
        RecordingCapture workerCapture = new RecordingCapture();
        var traces = mock(PrivateAgentTraceService.class);
        when(traces.recover(any(ResourceRef.class), eq("alice"))).thenReturn(workerCapture);
        when(lessons.startGeneration(
                        eq(planId), eq("alice"), eq(run), any(CaptureHandle.class)))
                .thenReturn(continuation);
        when(lessons.continueGeneration(eq(continuation), any(CaptureHandle.class)))
                .thenReturn(outcome);
        AtomicReference<Runnable> queuedStartup = new AtomicReference<>();
        AtomicReference<List<ResourceRef>> bindingsAtDispatch = new AtomicReference<>();
        TaskExecutor startup = task -> {
            bindingsAtDispatch.set(List.copyOf(requestCapture.boundResources));
            queuedStartup.set(task);
        };
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                startup,
                Runnable::run,
                Runnable::run,
                null,
                Optional.of(traces));

        launcher.launch(planId, "alice", requestCapture);

        ResourceRef teachingRun = new ResourceRef(ResourceType.TEACHING_RUN, run.id());
        assertThat(bindingsAtDispatch.get()).containsExactly(
                teachingRun,
                new ResourceRef(ResourceType.ASSISTANT_RUN, run.id()),
                new ResourceRef(ResourceType.TEACHING_PLAN, planId));
        assertThat(queuedStartup.get()).isNotNull();
        verify(lessons, never()).startGeneration(planId, "alice", run);

        queuedStartup.get().run();

        verify(traces, org.mockito.Mockito.atLeastOnce()).recover(teachingRun, "alice");
        verify(lessons).startGeneration(
                eq(planId),
                eq("alice"),
                eq(run),
                argThat(capture -> capture != null && capture.traceId().equals(workerCapture.traceId())));
        verify(lessons, never()).startGeneration(planId, "alice", run);
        verify(lessons).continueGeneration(
                eq(continuation),
                argThat(capture -> capture != null && capture.traceId().equals(workerCapture.traceId())));
        verify(lessons, never()).continueGeneration(continuation);
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
    void enriches_visuals_only_after_the_base_lesson_is_finished() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var visuals = mock(VisualLessonEnrichmentService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.DRAFT_READY);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        when(visuals.supportsVisualEvidence("alice")).thenReturn(true);
        when(visuals.launch(planId, "alice")).thenReturn(new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                UUID.randomUUID(), AssistantRunState.RECEIVED, 1, false));
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor(), visuals);

        launcher.launch(planId, "alice");

        verify(lessons).finish(outcome);
        verify(visuals).launch(planId, "alice");
        verify(visuals).enrichLatest(org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
        verify(visuals, never()).extractIconGlossaryOnly(
                org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
    }

    @Test
    void sends_optional_visual_work_to_its_own_executor() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var visuals = mock(VisualLessonEnrichmentService.class);
        AtomicReference<Runnable> queuedVisualWork = new AtomicReference<>();
        TaskExecutor lessonExecutor = Runnable::run;
        TaskExecutor visualExecutor = queuedVisualWork::set;
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        when(visuals.supportsVisualEvidence("alice")).thenReturn(true);
        when(visuals.launch(planId, "alice")).thenReturn(new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                UUID.randomUUID(), AssistantRunState.RECEIVED, 1, false));
        var launcher = new IllustratedLessonLauncher(lessons, runs, lessonExecutor, visualExecutor, visuals);

        launcher.launch(planId, "alice");

        verify(lessons).finish(outcome);
        verify(visuals).launch(planId, "alice");
        verify(visuals, never()).enrichLatest(org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
        assertThat(queuedVisualWork.get()).isNotNull();

        queuedVisualWork.get().run();

        verify(visuals).enrichLatest(org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
    }

    @Test
    void skipsOptionalVisualWorkWhenTheOwnerHasOnlyTextModels() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var visuals = mock(VisualLessonEnrichmentService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var continuation = continuation(run);
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.startGeneration(planId, "alice", run)).thenReturn(continuation);
        when(lessons.continueGeneration(continuation)).thenReturn(outcome);
        when(visuals.supportsVisualEvidence("alice")).thenReturn(false);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor(), visuals);

        launcher.launch(planId, "alice");

        verify(lessons).finish(outcome);
        verify(visuals, never()).launch(planId, "alice");
        verify(visuals, never()).enrichLatest(
                org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
    }

    @Test
    void preparesIconGlossaryWithoutRerunningLessonVisualLocalization() {
        var visuals = mock(VisualLessonEnrichmentService.class);
        var launch = new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                UUID.randomUUID(), AssistantRunState.RECEIVED, 1, false);
        when(visuals.launch(planId, "alice")).thenReturn(launch);
        var launcher = new IllustratedLessonLauncher(
                lessons, runs, new SyncTaskExecutor(), new SyncTaskExecutor(), visuals);

        var accepted = launcher.prepareIconGlossary(planId, "alice");

        assertThat(accepted).isEqualTo(launch);
        verify(visuals).extractIconGlossaryOnly(
                org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
        verify(visuals, never()).enrichLatest(
                org.mockito.ArgumentMatchers.eq(planId), any(RunSnapshot.class));
    }

    @Test
    void carriesTheActiveCaptureIntoBackgroundIconGlossaryProviders() {
        var visuals = mock(VisualLessonEnrichmentService.class);
        var launch = new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                UUID.randomUUID(), AssistantRunState.RECEIVED, 1, false);
        RecordingCapture capture = new RecordingCapture();
        when(visuals.launch(planId, "alice")).thenReturn(launch);
        var launcher = new IllustratedLessonLauncher(
                lessons, runs, new SyncTaskExecutor(), new SyncTaskExecutor(), visuals);

        launcher.prepareIconGlossary(planId, "alice", capture);

        verify(visuals).extractIconGlossaryOnly(
                eq(planId),
                any(RunSnapshot.class),
                argThat(actual -> actual != null && actual.traceId().equals(capture.traceId())));
        verify(visuals, never()).extractIconGlossaryOnly(eq(planId), any(RunSnapshot.class));
    }

    @Test
    void recoversAndRebindsReusedVisualRunsForBothVisualEntryPoints() {
        UUID visualRunId = UUID.randomUUID();
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, visualRunId);
        ResourceRef visualRun = new ResourceRef(ResourceType.VISUAL_RUN, visualRunId);
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, planId);
        var visuals = mock(VisualLessonEnrichmentService.class);
        var traces = mock(PrivateAgentTraceService.class);
        RecordingCapture recovered = new RecordingCapture();
        var reused = new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                visualRunId, AssistantRunState.RETRIEVING, 3, true);
        when(visuals.launch(planId, "alice")).thenReturn(reused);
        when(traces.recover(assistantRun, "alice")).thenReturn(recovered);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                visuals,
                Optional.of(traces));

        var enrichment = launcher.enrichLatest(planId, "alice");
        var glossary = launcher.prepareIconGlossary(planId, "alice");

        assertThat(enrichment).isEqualTo(reused);
        assertThat(glossary).isEqualTo(reused);
        verify(traces, org.mockito.Mockito.times(2)).recover(assistantRun, "alice");
        assertThat(recovered.boundResources).containsExactly(
                assistantRun, visualRun, plan,
                assistantRun, visualRun, plan);
        assertThat(recovered.lifecycleEvents)
                .filteredOn(event -> event.signal() == LifecycleSignal.REPLAY)
                .extracting(BindingOrFailure::code)
                .containsExactly(
                        "VISUAL_ENRICHMENT_REUSED",
                        "VISUAL_ASSISTANT_RUN_REUSED",
                        "ICON_GLOSSARY_REUSED",
                        "VISUAL_ASSISTANT_RUN_REUSED");
        verify(visuals, never()).enrichLatest(eq(planId), any(RunSnapshot.class));
        verify(visuals, never()).extractIconGlossaryOnly(eq(planId), any(RunSnapshot.class));
    }

    @Test
    void recordsAGapWhenAReusedVisualRunCannotBeClaimed() {
        UUID visualRunId = UUID.randomUUID();
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, visualRunId);
        ResourceRef visualRun = new ResourceRef(ResourceType.VISUAL_RUN, visualRunId);
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, planId);
        RecordingCapture current = new RecordingCapture(assistantRun);
        var visuals = mock(VisualLessonEnrichmentService.class);
        var traces = mock(PrivateAgentTraceService.class);
        var reused = new VisualLessonEnrichmentService.VisualEnrichmentLaunch(
                visualRunId, AssistantRunState.RETRIEVING, 3, true);
        when(visuals.launch(planId, "alice")).thenReturn(reused);
        when(traces.recover(assistantRun, "alice")).thenReturn(CaptureHandle.noop());
        when(traces.recover(visualRun, "alice")).thenReturn(CaptureHandle.noop());
        when(traces.recover(plan, "alice")).thenReturn(CaptureHandle.noop());
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                visuals,
                Optional.of(traces));

        var launch = launcher.enrichLatest(planId, "alice", current);

        assertThat(launch).isEqualTo(reused);
        assertThat(current.lifecycleEvents)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.signal()).isEqualTo(LifecycleSignal.GAP);
                    assertThat(event.code()).isEqualTo("VISUAL_RUN_REUSE_GAP");
                });
        verify(visuals, never()).enrichLatest(eq(planId), any(RunSnapshot.class));
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
    void recoversAndRebindsAnExistingTeachingRunBeforeReportingReplay() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        ResourceRef teachingRun = new ResourceRef(ResourceType.TEACHING_RUN, run.id());
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, run.id());
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, planId);
        RecordingCapture recovered = new RecordingCapture();
        var traces = mock(PrivateAgentTraceService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(traces.recover(teachingRun, "alice")).thenReturn(recovered);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                null,
                Optional.of(traces));

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.reused()).isTrue();
        verify(traces).recover(teachingRun, "alice");
        assertThat(recovered.boundResources).containsExactly(teachingRun, assistantRun, plan);
        assertThat(recovered.lifecycleEvents)
                .extracting(BindingOrFailure::signal, BindingOrFailure::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LifecycleSignal.REPLAY, "TEACHING_RUN_REUSED"),
                        org.assertj.core.groups.Tuple.tuple(
                                LifecycleSignal.REPLAY, "TEACHING_ASSISTANT_RUN_REUSED"));
        verify(lessons, never()).begin(planId, "alice");
    }

    @Test
    void recoversAnExistingTeachingRunOnTheImmediatePreparationLane() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        ResourceRef teachingRun = new ResourceRef(ResourceType.TEACHING_RUN, run.id());
        RecordingCapture recovered = new RecordingCapture();
        TeachingPlan plan = mock(TeachingPlan.class);
        var traces = mock(PrivateAgentTraceService.class);
        when(plan.id()).thenReturn(planId);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(traces.recover(teachingRun, "alice")).thenReturn(recovered);
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                null,
                Optional.of(traces));

        var launch = launcher.launchImmediately(plan, "alice", CaptureHandle.noop());

        assertThat(launch.reused()).isTrue();
        verify(traces).recover(teachingRun, "alice");
        assertThat(recovered.lifecycleEvents)
                .extracting(BindingOrFailure::code)
                .contains("TEACHING_RUN_REUSED");
        verify(lessons, never()).begin(plan, "alice");
    }

    @Test
    void recordsAGapWhenAnExistingTeachingRunCannotBeClaimed() {
        RunSnapshot run = run(AssistantRunState.RETRIEVING);
        ResourceRef teachingRun = new ResourceRef(ResourceType.TEACHING_RUN, run.id());
        RecordingCapture current = new RecordingCapture(teachingRun);
        var traces = mock(PrivateAgentTraceService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(run)));
        when(traces.recover(teachingRun, "alice")).thenReturn(CaptureHandle.noop());
        when(traces.recover(new ResourceRef(ResourceType.ASSISTANT_RUN, run.id()), "alice"))
                .thenReturn(CaptureHandle.noop());
        var launcher = new IllustratedLessonLauncher(
                lessons,
                runs,
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                new SyncTaskExecutor(),
                null,
                Optional.of(traces));

        var launch = launcher.launch(planId, "alice", current);

        assertThat(launch.reused()).isTrue();
        assertThat(current.lifecycleEvents)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.signal()).isEqualTo(LifecycleSignal.GAP);
                    assertThat(event.code()).isEqualTo("TEACHING_RUN_REUSE_GAP");
                });
        verify(lessons, never()).begin(planId, "alice");
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
        verify(lessons).failScheduling(run);
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
        verify(lessons).failScheduling(run);
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
                continuationLane,
                Runnable::run,
                null);

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
                rejectingContinuation,
                Runnable::run,
                null);

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons).startGeneration(planId, "alice", run);
        verify(lessons).failContinuationScheduling(continuation);
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
                brokenContinuation,
                Runnable::run,
                null);

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        verify(lessons).failContinuationScheduling(continuation);
        verify(lessons, never()).failScheduling(run);
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
                queuedContinuation::set,
                Runnable::run,
                null);

        launcher.launch(planId, "alice");

        assertThat(queuedContinuation.get()).isNull();
        verify(lessons).continueGeneration(continuation);
        verify(lessons).finish(outcome);
    }

    private RunSnapshot run(AssistantRunState state) {
        Instant now = Instant.parse("2026-07-20T10:00:00Z");
        return new RunSnapshot(
                UUID.randomUUID(), AssistantRunMode.TEACHING, planId, "alice", state, 0, now, now, null, null);
    }

    private RunDetails details(RunSnapshot run) {
        var budget = new AgentExecutionControl.BudgetSnapshot(
                40, 24, 16, 24_000, 0, 0, 0, run.createdAt().plusSeconds(120), null);
        return new RunDetails(run, List.of(), budget, List.of());
    }

    private GenerationContinuation continuation(RunSnapshot run) {
        BaseLessonContinuation base = mock(BaseLessonContinuation.class);
        when(base.hasRemainingWork()).thenReturn(true);
        return new GenerationContinuation(run, base);
    }

    private static final class RecordingCapture implements CaptureHandle {
        private final UUID traceId = UUID.randomUUID();
        private final Set<ResourceRef> rejectedResources;
        private final List<ResourceRef> boundResources = new ArrayList<>();
        private final List<BindingOrFailure> lifecycleEvents = new ArrayList<>();

        private RecordingCapture(ResourceRef... rejectedResources) {
            this.rejectedResources = Set.of(rejectedResources);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<UUID> traceId() {
            return Optional.of(traceId);
        }

        @Override
        public void userTurn(AgentTraceEvent.UserTurn event) {}

        @Override
        public void modelCallStarted(AgentTraceEvent.ModelCallStarted event) {}

        @Override
        public void modelTurn(AgentTraceEvent.ModelTurn event) {}

        @Override
        public void toolCall(AgentTraceEvent.ToolCall event) {}

        @Override
        public void toolObservation(AgentTraceEvent.ToolObservation event) {}

        @Override
        public void publication(AgentTraceEvent.Publication event) {}

        @Override
        public void bindingOrFailure(BindingOrFailure event) {
            lifecycleEvents.add(event);
        }

        @Override
        public boolean bind(ResourceRef resource) {
            boundResources.add(resource);
            return !rejectedResources.contains(resource);
        }
    }
}
