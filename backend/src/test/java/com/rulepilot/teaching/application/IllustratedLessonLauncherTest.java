package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationOutcome;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.generate(planId, "alice", run)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor());

        var launch = launcher.launch(planId, "alice");

        assertThat(launch.assistantRunId()).isEqualTo(run.id());
        assertThat(launch.reused()).isFalse();
        verify(lessons).generate(planId, "alice", run);
        verify(lessons).finish(outcome);
    }

    @Test
    void enriches_visuals_only_after_the_base_lesson_is_finished() {
        RunSnapshot run = run(AssistantRunState.RECEIVED);
        var visuals = mock(VisualLessonEnrichmentService.class);
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(Optional.empty());
        when(lessons.begin(planId, "alice")).thenReturn(run);
        var outcome = new GenerationOutcome(run, LessonStatus.DRAFT_READY);
        when(lessons.generate(planId, "alice", run)).thenReturn(outcome);
        var launcher = new IllustratedLessonLauncher(lessons, runs, new SyncTaskExecutor(), visuals);

        launcher.launch(planId, "alice");

        var order = inOrder(lessons, visuals);
        order.verify(lessons).finish(outcome);
        order.verify(visuals).enrichLatest(planId);
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
        verify(lessons, never()).generate(planId, "alice", run);
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
}
