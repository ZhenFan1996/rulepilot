package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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
import java.util.concurrent.atomic.AtomicReference;
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
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.generate(planId, "alice", run)).thenReturn(outcome);
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
        var outcome = new GenerationOutcome(run, LessonStatus.COMPLETE);
        when(lessons.generate(planId, "alice", run)).thenReturn(outcome);
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
