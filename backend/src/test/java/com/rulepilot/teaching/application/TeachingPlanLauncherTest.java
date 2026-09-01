package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.rulepilot.teaching.application.IllustratedLessonLauncher.LessonLaunch;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class TeachingPlanLauncherTest {

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
    void carriesTheActiveCaptureAcrossBackgroundPlanAndLessonStartup() {
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);
        RunSnapshot ready = run(received.id(), AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planning = run(received.id(), AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot completed = run(received.id(), AssistantRunState.COMPLETED, 4);
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        RecordingCapture capture = new RecordingCapture();
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
        when(plans.create(
                        eq(documentVersionId),
                        isNull(),
                        eq("alice"),
                        eq(received.id()),
                        argThat(actual -> actual != null && actual.traceId().equals(capture.traceId()))))
                .thenReturn(plan);
        when(lessons.launchImmediately(
                        eq(plan),
                        eq("alice"),
                        argThat(actual -> actual != null && actual.traceId().equals(capture.traceId()))))
                .thenReturn(new LessonLaunch(UUID.randomUUID(), AssistantRunState.RECEIVED, false));
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);

        launcher().launch(documentVersionId, null, "alice", capture);

        verify(plans).create(
                eq(documentVersionId),
                isNull(),
                eq("alice"),
                eq(received.id()),
                argThat(actual -> actual != null && actual.traceId().equals(capture.traceId())));
        verify(lessons).launchImmediately(
                eq(plan),
                eq("alice"),
                argThat(actual -> actual != null && actual.traceId().equals(capture.traceId())));
        assertThat(capture.boundResources).contains(
                new ResourceRef(ResourceType.ASSISTANT_RUN, received.id()),
                new ResourceRef(ResourceType.DOCUMENT_VERSION, documentVersionId),
                new ResourceRef(ResourceType.TEACHING_PLAN, planId));
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
    void recoversAndRebindsAnActivePreparationBeforeReportingReplay() {
        RunSnapshot planning = run(AssistantRunState.LESSON_PLANNING, 3);
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, planning.id());
        ResourceRef document = new ResourceRef(ResourceType.DOCUMENT_VERSION, documentVersionId);
        RecordingCapture recovered = new RecordingCapture();
        var traces = mock(PrivateAgentTraceService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.of(details(planning)));
        when(traces.recover(assistantRun, "alice")).thenReturn(recovered);
        var launcher = new TeachingPlanLauncher(
                plans,
                lessons,
                runs,
                new SyncTaskExecutor(),
                metrics,
                Optional.of(traces));

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.reused()).isTrue();
        verify(traces).recover(assistantRun, "alice");
        assertThat(recovered.boundResources).containsExactly(assistantRun, document);
        assertThat(recovered.lifecycleEvents)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.signal()).isEqualTo(LifecycleSignal.REPLAY);
                    assertThat(event.code()).isEqualTo("TEACHING_PREPARATION_REUSED");
                    assertThat(event.parentResource()).isEqualTo(document);
                    assertThat(event.childResource()).isEqualTo(assistantRun);
                });
        verify(runs, never()).start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice");
    }

    @Test
    void recordsAGapWhenAnActivePreparationCannotBeClaimedByTheCurrentTrace() {
        RunSnapshot planning = run(AssistantRunState.LESSON_PLANNING, 3);
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, planning.id());
        RecordingCapture current = new RecordingCapture(assistantRun);
        var traces = mock(PrivateAgentTraceService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.of(details(planning)));
        when(traces.recover(assistantRun, "alice")).thenReturn(CaptureHandle.noop());
        var launcher = new TeachingPlanLauncher(
                plans,
                lessons,
                runs,
                new SyncTaskExecutor(),
                metrics,
                Optional.of(traces));

        var launch = launcher.launch(documentVersionId, null, "alice", current);

        assertThat(launch.reused()).isTrue();
        assertThat(current.lifecycleEvents)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.signal()).isEqualTo(LifecycleSignal.GAP);
                    assertThat(event.code()).isEqualTo("TEACHING_PREPARATION_REUSE_GAP");
                });
        verify(runs, never()).start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice");
    }

    @Test
    void recordsARecoverableFailureWhenPlanningFailsInTheBackground() {
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
                received.id(), 3, "TEACHING_PREPARATION_FAILED", "Teaching preparation failed safely");
        verify(lessons, never()).launch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotCompletePreparationWhenLessonGenerationCannotBeScheduled() {
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
                received.id(), 3, "TEACHING_PREPARATION_FAILED", "Teaching preparation failed safely");
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
                40, 72, 36, 160_000, 0, 0, 0, run.createdAt().plusSeconds(600), null);
        return new RunDetails(run, List.of(), budget, List.of());
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
