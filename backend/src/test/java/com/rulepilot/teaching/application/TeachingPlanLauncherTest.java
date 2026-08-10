package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class TeachingPlanLauncherTest {

    private final TeachingPlanService plans = mock(TeachingPlanService.class);
    private final IllustratedLessonLauncher lessons = mock(IllustratedLessonLauncher.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
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
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(received.id());
        assertThat(launch.state()).isEqualTo(AssistantRunState.RECEIVED);
        assertThat(launch.reused()).isFalse();
        verify(lessons).launch(planId, "alice");
    }

    @Test
    void reusesAnActivePreparationForTheSameRulebook() {
        RunSnapshot planning = run(AssistantRunState.LESSON_PLANNING, 3);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice"))
                .thenReturn(Optional.of(details(planning)));
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launch(documentVersionId, "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.assistantRunId()).isEqualTo(planning.id());
        verify(runs, never()).start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, "alice");
        verifyNoInteractions(plans);
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
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

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
        when(lessons.launch(planId, "alice"))
                .thenThrow(new IllegalStateException("teaching queue unavailable"));
        when(runs.findOwned(received.id(), "alice")).thenReturn(Optional.of(details(planning)));
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

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
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

        launcher.launch(documentVersionId, learningGoal, "alice");

        verify(plans, never()).create(
                documentVersionId, learningGoal, "alice", received.id());
        verify(lessons).launch(planId, "alice");
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
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

        launcher.launch(documentVersionId, "alice");

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
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

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
        when(runs.advance(received.id(), 3, AssistantRunState.COMPLETED, "Teaching plan is ready"))
                .thenReturn(completed);
        var launcher = new TeachingPlanLauncher(plans, lessons, runs, new SyncTaskExecutor());

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
}
