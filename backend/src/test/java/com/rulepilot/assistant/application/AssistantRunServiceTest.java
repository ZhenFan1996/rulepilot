package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns.StepSnapshot;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.domain.AssistantRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AssistantRunServiceTest {

    @Test
    void startsQueuedPreparationDeadlineWhenTheWorkerActuallyAdmitsIt() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant queuedAt = Instant.now().minus(Duration.ofHours(2));
        AssistantRun queued = AssistantRun.start(
                AssistantRunMode.TEACHING_PREPARATION,
                UUID.randomUUID(),
                "player",
                queuedAt);
        when(repository.find(queued.id())).thenReturn(java.util.Optional.of(queued));
        var snapshot = new com.rulepilot.assistant.AssistantRuns.RunSnapshot(
                queued.id(),
                queued.mode(),
                queued.subjectId(),
                queued.ownerUsername(),
                queued.state(),
                queued.revision(),
                queued.createdAt(),
                queued.updatedAt(),
                queued.completedAt(),
                queued.lastErrorCode());
        UUID activationId = UUID.randomUUID();
        Instant admittedAt = Instant.now();
        service.activateQueued(snapshot, activationId, admittedAt);

        verify(execution).activate(queued.id(), activationId, admittedAt);
        assertThat(admittedAt).isAfter(queuedAt.plus(Duration.ofHours(1)));
    }

    @Test
    void startsQueuedTeachingDeadlineWhenItsFirstWorkerActuallyAdmitsIt() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant queuedAt = Instant.now().minus(Duration.ofMinutes(20));
        AssistantRun queued = AssistantRun.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                queuedAt);
        when(repository.find(queued.id())).thenReturn(java.util.Optional.of(queued));
        var snapshot = snapshot(queued);
        UUID activationId = UUID.randomUUID();
        Instant admittedAt = Instant.now();
        service.activateQueued(snapshot, activationId, admittedAt);

        verify(execution).activate(queued.id(), activationId, admittedAt);
        assertThat(admittedAt).isAfter(queuedAt.plus(Duration.ofMinutes(19)));
    }

    @Test
    void recordsAQueuedFailureOnlyWhileTheDurableAdmissionRowIsStillUnclaimed() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        AssistantRun queued = AssistantRun.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                Instant.now().minusSeconds(30));
        when(execution.lockUnactivated(queued.id())).thenReturn(true);
        when(repository.find(queued.id())).thenReturn(java.util.Optional.of(queued));
        when(repository.update(any(), any(), any())).thenReturn(true);

        boolean failed = service.failQueuedIfUnactivated(
                queued.id(),
                "player",
                "TEACHING_QUEUE_TIMEOUT",
                "Teaching generation waited too long for a worker and is safe to retry");

        assertThat(failed).isTrue();
        ArgumentCaptor<AssistantRun> changed = ArgumentCaptor.forClass(AssistantRun.class);
        verify(repository).update(eq(queued), changed.capture(), any());
        assertThat(changed.getValue().state()).isEqualTo(AssistantRunState.FAILED);
        assertThat(changed.getValue().lastErrorCode()).isEqualTo("TEACHING_QUEUE_TIMEOUT");
    }

    @Test
    void aDurableWorkerClaimWinsBeforeAStaleInMemoryQueueExpiryCanFailTheRun() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        UUID runId = UUID.randomUUID();
        when(execution.lockUnactivated(runId)).thenReturn(false);

        boolean failed = service.failQueuedIfUnactivated(
                runId,
                "player",
                "TEACHING_QUEUE_TIMEOUT",
                "Teaching generation waited too long for a worker and is safe to retry");

        assertThat(failed).isFalse();
        verify(repository, never()).find(runId);
        verify(repository, never()).update(any(), any(), any());
        verify(execution, never()).stopRunning(any(), any(), any());
    }

    @Test
    void aFailedDeliveryMayTerminateItsOwnAmbiguousClaimButNeverAnotherDeliverysClaim() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        AssistantRun queued = AssistantRun.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                Instant.now().minusSeconds(30));
        UUID activationId = UUID.randomUUID();
        when(execution.lockUnactivatedOrOwned(queued.id(), activationId)).thenReturn(true);
        when(repository.find(queued.id())).thenReturn(java.util.Optional.of(queued));
        when(repository.update(any(), any(), any())).thenReturn(true);

        assertThat(service.failQueuedIfUnactivatedOrOwned(
                        queued.id(),
                        "player",
                        activationId,
                        "TEACHING_WORKER_ADMISSION_FAILED",
                        "Teaching generation could not acquire its durable worker lease"))
                .isTrue();

        ArgumentCaptor<AssistantRun> changed = ArgumentCaptor.forClass(AssistantRun.class);
        verify(repository).update(eq(queued), changed.capture(), any());
        assertThat(changed.getValue().lastErrorCode()).isEqualTo("TEACHING_WORKER_ADMISSION_FAILED");

        UUID otherActivation = UUID.randomUUID();
        when(execution.lockUnactivatedOrOwned(queued.id(), otherActivation)).thenReturn(false);
        assertThat(service.failQueuedIfUnactivatedOrOwned(
                        queued.id(),
                        "player",
                        otherActivation,
                        "TEACHING_WORKER_ADMISSION_FAILED",
                        "Teaching generation could not acquire its durable worker lease"))
                .isFalse();
    }

    @Test
    void failsAnAdmittedContinuationOnlyWhileItStillOwnsANonTerminalRun() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(30);
        AssistantRun retrieving = AssistantRun.start(
                        AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.DOCUMENT_READINESS, startedAt.plusSeconds(1))
                .advance(AssistantRunState.LESSON_PLANNING, startedAt.plusSeconds(2))
                .advance(AssistantRunState.RETRIEVAL_PLANNING, startedAt.plusSeconds(3))
                .advance(AssistantRunState.RETRIEVING, startedAt.plusSeconds(4));
        when(repository.find(retrieving.id())).thenReturn(java.util.Optional.of(retrieving));
        when(repository.update(any(), any(), any())).thenReturn(true);

        boolean failed = service.failActiveIfOwned(
                retrieving.id(),
                "player",
                "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                "The first cited section is readable but remaining teaching work waited too long for a worker");

        assertThat(failed).isTrue();
        verify(execution).assertFinalizationAllowed(retrieving.id());
        ArgumentCaptor<AssistantRun> changed = ArgumentCaptor.forClass(AssistantRun.class);
        verify(repository).update(eq(retrieving), changed.capture(), any());
        assertThat(changed.getValue().state()).isEqualTo(AssistantRunState.FAILED);
        assertThat(changed.getValue().lastErrorCode()).isEqualTo("TEACHING_CONTINUATION_QUEUE_TIMEOUT");
    }

    @Test
    void treatsACompletedRunAsDurablySettledWithoutOverwritingIt() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(30);
        AssistantRun completed = AssistantRun.start(
                        AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.DOCUMENT_READINESS, startedAt.plusSeconds(1))
                .advance(AssistantRunState.LESSON_PLANNING, startedAt.plusSeconds(2))
                .advance(AssistantRunState.RETRIEVAL_PLANNING, startedAt.plusSeconds(3))
                .advance(AssistantRunState.RETRIEVING, startedAt.plusSeconds(4))
                .advance(AssistantRunState.VERIFYING_EVIDENCE, startedAt.plusSeconds(5))
                .advance(AssistantRunState.LESSON_COMPOSITION, startedAt.plusSeconds(6))
                .advance(AssistantRunState.COMPLETED, startedAt.plusSeconds(7));
        when(repository.find(completed.id())).thenReturn(java.util.Optional.of(completed));

        boolean settled = service.failActiveIfOwned(
                completed.id(),
                "player",
                "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                "The first cited section is readable but remaining teaching work waited too long for a worker");

        assertThat(settled).isTrue();
        verify(execution, never()).assertFinalizationAllowed(completed.id());
        verify(repository, never()).update(any(), any(), any());
    }

    @Test
    void treatsAnAlreadyDeletedContinuationRunAsSettled() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        UUID runId = UUID.randomUUID();
        when(repository.find(runId)).thenReturn(java.util.Optional.empty());

        assertThat(service.failActiveIfOwned(
                        runId,
                        "player",
                        "TEACHING_CONTINUATION_QUEUE_TIMEOUT",
                        "The first cited section is readable but remaining teaching work waited too long for a worker"))
                .isTrue();

        verify(execution, never()).assertFinalizationAllowed(runId);
        verify(repository, never()).update(any(), any(), any());
    }

    @Test
    void excludesOnlyContinuationQueueWaitFromAnActiveTeachingDeadline() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(3));
        AssistantRun retrieving = AssistantRun.start(
                        AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.DOCUMENT_READINESS, startedAt.plusSeconds(1))
                .advance(AssistantRunState.LESSON_PLANNING, startedAt.plusSeconds(2))
                .advance(AssistantRunState.RETRIEVAL_PLANNING, startedAt.plusSeconds(3))
                .advance(AssistantRunState.RETRIEVING, startedAt.plusSeconds(4));
        when(repository.find(retrieving.id())).thenReturn(java.util.Optional.of(retrieving));
        Duration queueWait = Duration.ofSeconds(45);

        service.resumeAfterQueue(snapshot(retrieving), queueWait);

        verify(execution).excludeQueueWait(retrieving.id(), queueWait);
    }

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
                24_000,
                Duration.ofMinutes(2),
                40,
                300_000,
                Duration.ofMinutes(30),
                Duration.ofHours(16),
                16_000_000,
                600_000,
                Duration.ofMinutes(30));

        service.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.VISUAL_ENRICHMENT, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player");

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution, times(3)).initialize(any(), limits.capture(), any());
        assertThat(limits.getAllValues())
                .extracting(BudgetLimits::maxTokens, BudgetLimits::timeout, BudgetLimits::tokenLimitEnforced)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300_000, Duration.ofMinutes(30), false),
                        org.assertj.core.groups.Tuple.tuple(600_000, Duration.ofMinutes(30), false),
                        org.assertj.core.groups.Tuple.tuple(24_000, Duration.ofMinutes(2), true));
    }

    @Test
    void sizesResourcesFromThePlanEstimateWithoutPersistingItAsACallLimit() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(127));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().maxTokens()).isEqualTo(529_167);
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofMinutes(52).plusSeconds(55));
        assertThat(limits.getValue().tokenLimitEnforced()).isFalse();
    }

    @Test
    void keepsTheOrdinaryTeachingTimeoutWhenTheAdmittedGraphFitsTheConfiguredBaseline() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(72));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(limits.getValue().maxTokens()).isEqualTo(300_000);
    }

    @Test
    void extendsTheTeachingDeadlineProportionallyForASmallerAdmittedGraph() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(108));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().maxTokens()).isEqualTo(450_000);
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void initializesTwentyPagePreparationWithItsCollapsedPageOwnedCallGraph() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING_PREPARATION,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(128));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().maxTokens()).isEqualTo(533_334);
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofMinutes(53).plusSeconds(20));
    }

    @Test
    void givesTheLargestAcceptedVisualPreparationHeadroomBelowTheConfiguredHardDeadline() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING_PREPARATION,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(3_008));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().maxTokens()).isEqualTo(12_533_334);
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofHours(16));
    }

    @Test
    void capsTheScaledTokenEnvelopeForAWorkloadBeyondTheAcceptedDocumentCeiling() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);

        service.start(
                AssistantRunMode.TEACHING_PREPARATION,
                UUID.randomUUID(),
                "player",
                new WorkloadDemand(3_888));

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution).initialize(any(), limits.capture(), any());
        assertThat(limits.getValue().maxTokens()).isEqualTo(16_000_000);
        assertThat(limits.getValue().timeout()).isEqualTo(Duration.ofHours(16));
    }

    @Test
    void rejectsAWorkloadTimeoutBoundaryBelowTheOrdinaryTeachingTimeout() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);

        assertThatThrownBy(() -> new AssistantRunService(
                        repository,
                        execution,
                        24_000,
                        Duration.ofMinutes(2),
                        72,
                        300_000,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(29),
                        16_000_000,
                        600_000,
                        Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum workload timeout");
    }

    @Test
    void rejectsAWorkloadTokenBoundaryBelowTheOrdinaryTeachingBudget() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);

        assertThatThrownBy(() -> new AssistantRunService(
                        repository,
                        execution,
                        24_000,
                        Duration.ofMinutes(2),
                        72,
                        300_000,
                        Duration.ofMinutes(30),
                        Duration.ofHours(16),
                        299_999,
                        600_000,
                        Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum workload tokens");
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
        verify(execution).assertFinalizationAllowed(retrieving.id());
        verify(execution, never()).assertStepAllowed(any(), any(Long.class));
    }

    @Test
    void locksTheCancellationBoundaryBeforeReadingTheRunForFinalization() {
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

        service.advanceAfterWork(
                retrieving.id(),
                retrieving.revision(),
                AssistantRunState.VERIFYING_EVIDENCE,
                "Lesson citations are scope checked");

        InOrder order = org.mockito.Mockito.inOrder(execution, repository);
        order.verify(execution).assertFinalizationAllowed(retrieving.id());
        order.verify(repository).find(retrieving.id());
        order.verify(repository).update(any(), any(), any());
    }

    @Test
    void ownerCancellationPreventsPostWorkTeachingFinalization() {
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
        org.mockito.Mockito.doThrow(new com.rulepilot.assistant.AgentExecutionStoppedException(
                        com.rulepilot.assistant.AgentExecutionStoppedException.StopReason.CANCELLED))
                .when(execution).assertFinalizationAllowed(retrieving.id());

        assertThatThrownBy(() -> service.advanceAfterWork(
                        retrieving.id(),
                        retrieving.revision(),
                        AssistantRunState.VERIFYING_EVIDENCE,
                        "Lesson citations are scope checked"))
                .isInstanceOf(com.rulepilot.assistant.AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue(
                        "reason",
                        com.rulepilot.assistant.AgentExecutionStoppedException.StopReason.CANCELLED);

        verify(repository, never()).update(any(), any(), any());
    }

    @Test
    void treatsCancellationAfterTerminalCommitAsIdempotentlySatisfied() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        UUID runId = UUID.randomUUID();
        when(execution.requestCancellationIfActive(runId, "player")).thenReturn(false);

        service.requestCancellation(runId, "player");

        verify(repository, never()).find(any());
        verify(repository, never()).update(any(), any(), any());
        verify(execution, never()).stopRunning(any(), any(), any());
    }

    @Test
    void recordsOwnerCancellationOnlyAfterItWinsTheSharedBoundary() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(10);
        AssistantRun active = AssistantRun.start(
                AssistantRunMode.TEACHING, UUID.randomUUID(), "player", startedAt);
        when(execution.requestCancellationIfActive(active.id(), "player")).thenReturn(true);
        when(repository.find(active.id())).thenReturn(java.util.Optional.of(active));
        when(repository.update(any(), any(), any())).thenReturn(true);

        service.requestCancellation(active.id(), "player");

        ArgumentCaptor<AssistantRun> cancelled = ArgumentCaptor.forClass(AssistantRun.class);
        InOrder order = org.mockito.Mockito.inOrder(execution, repository);
        order.verify(execution).requestCancellationIfActive(active.id(), "player");
        order.verify(repository).find(active.id());
        order.verify(execution)
                .stopRunning(active.id(), AgentExecutionControl.ActivityOutcome.REJECTED, "Work stopped by the user");
        order.verify(repository).update(eq(active), cancelled.capture(), eq("Cancellation requested by run owner"));
        assertThat(cancelled.getValue().state()).isEqualTo(AssistantRunState.FAILED);
        assertThat(cancelled.getValue().lastErrorCode()).isEqualTo("AGENT_CANCELLED");
    }

    @Test
    void recordsAValidatedQuestionAnswerAfterTheNativeAgentWorkSettles() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(60);
        AssistantRun composing = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", startedAt)
                .advance(AssistantRunState.ANSWER_COMPOSITION, startedAt.plusSeconds(1));
        when(repository.find(composing.id())).thenReturn(java.util.Optional.of(composing));
        when(repository.update(any(), any(), any())).thenReturn(true);

        var completed = service.advanceAfterWork(
                        composing.id(), composing.revision(), AssistantRunState.COMPLETED,
                        "The answer Agent published its validated terminal response");

        assertThat(completed.state()).isEqualTo(AssistantRunState.COMPLETED);
        verify(execution).assertFinalizationAllowed(composing.id());
        verify(execution, never()).assertStepAllowed(any(), any(Long.class));
    }

    @Test
    void returnsLatestRunOnlyWithinTheRequestedOwnerAndSubject() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = new AssistantRunService(
                repository,
                execution,
                24_000,
                Duration.ofMinutes(2),
                40,
                300_000,
                Duration.ofMinutes(30),
                Duration.ofHours(16),
                16_000_000,
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
    void exposesTheSafeFailureCodeAndActualAnswerPhaseThroughOwnedRunDetails() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = service(repository, execution);
        Instant startedAt = Instant.now().minusSeconds(5);
        AssistantRun failed = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", startedAt)
                .fail("QUESTION_WORKFLOW_FAILED", startedAt.plusSeconds(1));
        StepSnapshot failureStep = new StepSnapshot(
                1,
                AssistantRunState.RECEIVED,
                AssistantRunState.FAILED,
                "Question workflow failed safely during RETRIEVING",
                startedAt.plusSeconds(1));
        when(repository.find(failed.id())).thenReturn(java.util.Optional.of(failed));
        when(repository.steps(failed.id())).thenReturn(List.of(failureStep));
        when(execution.activities(failed.id())).thenReturn(List.of());

        var details = service.findOwned(failed.id(), "player").orElseThrow();

        assertThat(details.run().lastErrorCode()).isEqualTo("QUESTION_WORKFLOW_FAILED");
        assertThat(details.steps()).last().extracting(StepSnapshot::summary)
                .isEqualTo("Question workflow failed safely during RETRIEVING");
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
                24_000,
                Duration.ofMinutes(2),
                72,
                300_000,
                Duration.ofMinutes(30),
                Duration.ofHours(16),
                16_000_000,
                600_000,
                Duration.ofMinutes(30));
    }

    private com.rulepilot.assistant.AssistantRuns.RunSnapshot snapshot(AssistantRun run) {
        return new com.rulepilot.assistant.AssistantRuns.RunSnapshot(
                run.id(),
                run.mode(),
                run.subjectId(),
                run.ownerUsername(),
                run.state(),
                run.revision(),
                run.createdAt(),
                run.updatedAt(),
                run.completedAt(),
                run.lastErrorCode());
    }
}
