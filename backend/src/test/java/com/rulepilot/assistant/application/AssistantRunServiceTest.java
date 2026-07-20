package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
                Duration.ofMinutes(30));

        service.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player");

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution, times(2)).initialize(any(), limits.capture(), any());
        assertThat(limits.getAllValues())
                .extracting(BudgetLimits::maxTokens, BudgetLimits::timeout)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300_000, Duration.ofMinutes(30)),
                        org.assertj.core.groups.Tuple.tuple(24_000, Duration.ofMinutes(2)));
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
                Duration.ofMinutes(30));
    }
}
