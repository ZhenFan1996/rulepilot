package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AssistantRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantRunServiceTest {

    @Test
    void turnsInterruptedTeachingRunsIntoRetryableFailuresOnStartup() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(60);
        AssistantRun interrupted = AssistantRun.start(
                        AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.DOCUMENT_READINESS, startedAt.plusSeconds(1));
        when(repository.findNonTerminal(AssistantRunMode.TEACHING)).thenReturn(List.of(interrupted));
        when(repository.update(any(), any(), any())).thenReturn(true);

        int recovered = service.failInterrupted(AssistantRunMode.TEACHING);

        assertThat(recovered).isOne();
        ArgumentCaptor<AssistantRun> changed = ArgumentCaptor.forClass(AssistantRun.class);
        verify(repository).update(any(), changed.capture(), any());
        assertThat(changed.getValue().state()).isEqualTo(AssistantRunState.FAILED);
        assertThat(changed.getValue().lastErrorCode()).isEqualTo("APPLICATION_RESTARTED");
    }

    @Test
    void givesTeachingRunsTheirDedicatedExecutionBudget() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = new AssistantRunService(
                repository,
                execution,
                72,
                24,
                16,
                24_000,
                Duration.ofMinutes(2),
                72,
                40,
                300_000,
                Duration.ofMinutes(30),
                192,
                600_000,
                Duration.ofMinutes(30));

        service.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.VISUAL_ENRICHMENT, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player");

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution, times(3)).initialize(any(), limits.capture(), any());
        assertThat(limits.getAllValues())
                .extracting(BudgetLimits::maxModelCalls, BudgetLimits::maxTokens, BudgetLimits::timeout)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(40, 300_000, Duration.ofMinutes(30)),
                        org.assertj.core.groups.Tuple.tuple(192, 600_000, Duration.ofMinutes(30)),
                        org.assertj.core.groups.Tuple.tuple(16, 24_000, Duration.ofMinutes(2)));
    }

    @Test
    void recordsFinalizationAfterModelWorkWithoutRecheckingTheExecutionBudget() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(60);
        AssistantRun retrieving = AssistantRun.start(
                        AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.DOCUMENT_READINESS, startedAt.plusSeconds(1))
                .advance(AssistantRunState.LESSON_PLANNING, startedAt.plusSeconds(2))
                .advance(AssistantRunState.RETRIEVAL_PLANNING, startedAt.plusSeconds(3))
                .advance(AssistantRunState.RETRIEVING, startedAt.plusSeconds(4));
        when(repository.find(retrieving.id())).thenReturn(java.util.Optional.of(retrieving));
        when(repository.update(any(), any(), any())).thenReturn(true);

        var verified = service.advanceAfterWork(
                retrieving.id(), retrieving.revision(), AssistantRunState.VERIFYING_EVIDENCE,
                "Lesson citations are scope checked");

        assertThat(verified.state()).isEqualTo(AssistantRunState.VERIFYING_EVIDENCE);
        verify(execution, never()).assertStepAllowed(any(), any(Long.class));
    }

    @Test
    void doesNotOfferPostWorkFinalizationToQuestionRuns() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(60);
        AssistantRun retrieving = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.QUESTION_UNDERSTANDING, startedAt.plusSeconds(1))
                .advance(AssistantRunState.RETRIEVAL_PLANNING, startedAt.plusSeconds(2))
                .advance(AssistantRunState.RETRIEVING, startedAt.plusSeconds(3));
        when(repository.find(retrieving.id())).thenReturn(java.util.Optional.of(retrieving));

        assertThatThrownBy(() -> service.advanceAfterWork(
                        retrieving.id(), retrieving.revision(), AssistantRunState.VERIFYING_EVIDENCE,
                        "Answer source scope is policy checked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("post-work finalization is only available to teaching runs");

        verify(execution, never()).assertStepAllowed(any(), any(Long.class));
    }

    @Test
    void returnsLatestRunOnlyWithinTheRequestedOwnerAndSubject() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = new AssistantRunService(
                repository,
                execution,
                72,
                24,
                16,
                24_000,
                Duration.ofMinutes(2),
                72,
                40,
                300_000,
                Duration.ofMinutes(30),
                192,
                600_000,
                Duration.ofMinutes(30));
        UUID planId = UUID.randomUUID();
        AssistantRun run = AssistantRun.start(AssistantRunMode.TEACHING, planId, "player", Instant.now());
        when(repository.findLatest(AssistantRunMode.TEACHING, planId, "player")).thenReturn(java.util.Optional.of(run));
        when(repository.steps(run.id())).thenReturn(List.of());
        when(execution.activities(run.id())).thenReturn(List.of());

        var details = service.findLatestOwned(AssistantRunMode.TEACHING, planId, "player");

        assertThat(details).isPresent();
        assertThat(details.orElseThrow().run().subjectId()).isEqualTo(planId);
        verify(repository).findLatest(AssistantRunMode.TEACHING, planId, "player");
    }

    @Test
    void appliesActivityCursorOnlyToTheSameLatestRun() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        UUID planId = UUID.randomUUID();
        AssistantRun run = AssistantRun.start(AssistantRunMode.TEACHING, planId, "player", Instant.now());
        when(repository.findLatest(AssistantRunMode.TEACHING, planId, "player"))
                .thenReturn(java.util.Optional.of(run));
        when(repository.steps(run.id())).thenReturn(List.of());
        when(execution.activitiesAfter(run.id(), 18)).thenReturn(List.of());
        when(execution.activitiesAfter(run.id(), 0)).thenReturn(List.of());

        service.findLatestOwned(AssistantRunMode.TEACHING, planId, "player", run.id(), 18);
        service.findLatestOwned(AssistantRunMode.TEACHING, planId, "player", UUID.randomUUID(), 18);

        verify(execution).activitiesAfter(run.id(), 18);
        verify(execution).activitiesAfter(run.id(), 0);
    }

    @Test
    void loadsACompleteAdministrativeAuditWithoutOwnerFiltering() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        AssistantRun run = AssistantRun.start(
                AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", Instant.now());
        when(repository.find(run.id())).thenReturn(java.util.Optional.of(run));
        when(repository.steps(run.id())).thenReturn(List.of());
        when(execution.activities(run.id())).thenReturn(List.of());

        var audit = service.findForAdministrativeAudit(run.id());

        assertThat(audit).isPresent();
        assertThat(audit.orElseThrow().run().ownerUsername()).isEqualTo("player");
        verify(repository).find(run.id());
        verify(execution).budget(run.id());
        verify(execution).activities(run.id());
    }

    @Test
    void returnsOnlyTheOwnersActiveRunsWithoutLoadingAuditDetails() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        AssistantRun active = AssistantRun.start(
                AssistantRunMode.TEACHING, UUID.randomUUID(), "player", Instant.now());
        when(repository.findNonTerminalOwned(AssistantRunMode.TEACHING, "player"))
                .thenReturn(List.of(active));

        var runs = service.findActiveOwned(AssistantRunMode.TEACHING, " player ");

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(active.id());
            assertThat(run.subjectId()).isEqualTo(active.subjectId());
            assertThat(run.ownerUsername()).isEqualTo("player");
        });
        verify(repository).findNonTerminalOwned(AssistantRunMode.TEACHING, "player");
        verify(execution, times(0)).activities(any());
    }

    private AssistantRunService service(AssistantRunRepository repository, AgentExecutionControl execution) {
        return new AssistantRunService(
                repository,
                execution,
                72,
                24,
                16,
                24_000,
                Duration.ofMinutes(2),
                72,
                40,
                300_000,
                Duration.ofMinutes(30),
                192,
                600_000,
                Duration.ofMinutes(30));
    }
}
