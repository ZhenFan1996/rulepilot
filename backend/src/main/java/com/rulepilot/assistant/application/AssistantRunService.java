package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.domain.AssistantRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AssistantRunService implements AssistantRuns {

    private final AssistantRunRepository repository;
    private final AgentExecutionControl execution;
    private final BudgetLimits answerLimits;
    private final BudgetLimits teachingLimits;
    private final BudgetLimits visualEnrichmentLimits;
    private final int teachingModelCallCapacityBaseline;
    private final Duration teachingMaxWorkloadTimeout;
    private final int teachingMaxWorkloadTokens;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    public AssistantRunService(
            AssistantRunRepository repository,
            AgentExecutionControl execution,
            @Value("${rulepilot.agent.max-tokens:64000}") int maxTokens,
            @Value("${rulepilot.agent.timeout:PT2M}") Duration timeout,
            @Value("${rulepilot.teaching.agent.model-call-capacity-baseline:72}")
                    int teachingModelCallCapacityBaseline,
            @Value("${rulepilot.teaching.agent.max-tokens:300000}") int teachingMaxTokens,
            @Value("${rulepilot.teaching.agent.timeout:PT30M}") Duration teachingTimeout,
            @Value("${rulepilot.teaching.agent.max-workload-timeout:PT16H}")
                    Duration teachingMaxWorkloadTimeout,
            @Value("${rulepilot.teaching.agent.max-workload-tokens:16000000}")
                    int teachingMaxWorkloadTokens,
            @Value("${rulepilot.teaching.visual-enrichment.agent.max-tokens:600000}")
                    int visualEnrichmentMaxTokens,
            @Value("${rulepilot.teaching.visual-enrichment.agent.timeout:PT30M}")
                    Duration visualEnrichmentTimeout) {
        this.repository = repository;
        this.execution = execution;
        this.answerLimits = new BudgetLimits(maxTokens, timeout);
        this.teachingLimits = new BudgetLimits(teachingMaxTokens, teachingTimeout);
        if (teachingModelCallCapacityBaseline < 1) {
            throw new IllegalArgumentException("teaching model-call capacity baseline must be positive");
        }
        this.teachingModelCallCapacityBaseline = teachingModelCallCapacityBaseline;
        if (teachingMaxWorkloadTimeout == null
                || teachingMaxWorkloadTimeout.isZero()
                || teachingMaxWorkloadTimeout.isNegative()
                || teachingMaxWorkloadTimeout.compareTo(teachingTimeout) < 0) {
            throw new IllegalArgumentException(
                    "teaching maximum workload timeout must be positive and no shorter than the teaching timeout");
        }
        this.teachingMaxWorkloadTimeout = teachingMaxWorkloadTimeout;
        if (teachingMaxWorkloadTokens < teachingMaxTokens) {
            throw new IllegalArgumentException(
                    "teaching maximum workload tokens must be positive and no lower than the teaching token budget");
        }
        this.teachingMaxWorkloadTokens = teachingMaxWorkloadTokens;
        this.visualEnrichmentLimits = new BudgetLimits(visualEnrichmentMaxTokens, visualEnrichmentTimeout);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        return start(mode, subjectId, ownerUsername, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot start(
            AssistantRunMode mode,
            UUID subjectId,
            String ownerUsername,
            WorkloadDemand workloadDemand) {
        BudgetLimits limits = limitsFor(mode, workloadDemand);
        AssistantRun run = AssistantRun.start(mode, subjectId, ownerUsername, Instant.now(clock));
        repository.insert(run, "Run received");
        execution.initialize(run.id(), limits, run.createdAt());
        return snapshot(run);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot activateQueued(RunSnapshot queued) {
        return activateQueued(queued, UUID.randomUUID());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot activateQueued(RunSnapshot queued, UUID activationId) {
        return activateQueued(queued, activationId, Instant.now(clock));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot activateQueued(RunSnapshot queued, UUID activationId, Instant admittedAt) {
        if (queued == null
                || activationId == null
                || admittedAt == null
                || admittedAt.isBefore(queued.createdAt())
                || (queued.mode() != AssistantRunMode.TEACHING_PREPARATION
                        && queued.mode() != AssistantRunMode.TEACHING)
                || queued.state() != AssistantRunState.RECEIVED) {
            throw new IllegalArgumentException("only received teaching work can leave its queue");
        }
        execution.activate(queued.id(), activationId, admittedAt);
        AssistantRun current = require(queued.id(), queued.revision());
        if (current.mode() != queued.mode()
                || !current.subjectId().equals(queued.subjectId())
                || !current.ownerUsername().equals(queued.ownerUsername())
                || current.state() != AssistantRunState.RECEIVED) {
            throw new IllegalStateException("queued teaching preparation identity changed before worker admission");
        }
        return snapshot(current);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot resumeAfterQueue(RunSnapshot queued, Duration queueWait) {
        if (queued == null
                || queueWait == null
                || queueWait.isNegative()
                || queued.mode() != AssistantRunMode.TEACHING
                || queued.state().terminal()) {
            throw new IllegalArgumentException("only active teaching work can resume from its queue");
        }
        execution.excludeQueueWait(queued.id(), queueWait);
        AssistantRun current = require(queued.id(), queued.revision());
        if (current.mode() != queued.mode()
                || !current.subjectId().equals(queued.subjectId())
                || !current.ownerUsername().equals(queued.ownerUsername())
                || current.state() != queued.state()
                || current.state().terminal()) {
            throw new IllegalStateException("queued teaching identity changed before worker admission");
        }
        return snapshot(current);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failQueuedIfUnactivated(
            UUID runId,
            String ownerUsername,
            String errorCode,
            String stepSummary) {
        if (runId == null || ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("queued assistant run identity is required");
        }
        validateSummary(stepSummary);
        if (!execution.lockUnactivated(runId)) return false;
        AssistantRun current = repository.find(runId).orElse(null);
        if (current == null) return false;
        if (!current.ownerUsername().equals(ownerUsername.strip())) {
            throw new IllegalArgumentException("queued assistant run does not exist");
        }
        if (current.state().terminal() || current.state() != AssistantRunState.RECEIVED) return false;
        AssistantRun failed = current.fail(errorCode, Instant.now(clock));
        execution.stopRunning(runId, AgentExecutionControl.ActivityOutcome.FAILED, stepSummary.strip());
        persist(current, failed, stepSummary);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failQueuedIfUnactivatedOrOwned(
            UUID runId,
            String ownerUsername,
            UUID activationId,
            String errorCode,
            String stepSummary) {
        if (runId == null || activationId == null || ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("queued assistant run admission identity is required");
        }
        validateSummary(stepSummary);
        if (!execution.lockUnactivatedOrOwned(runId, activationId)) return false;
        AssistantRun current = repository.find(runId).orElse(null);
        if (current == null) return true;
        if (!current.ownerUsername().equals(ownerUsername.strip())) {
            throw new IllegalArgumentException("queued assistant run does not exist");
        }
        if (current.state().terminal()) return true;
        if (current.state() != AssistantRunState.RECEIVED) return false;
        AssistantRun failed = current.fail(errorCode, Instant.now(clock));
        execution.stopRunning(runId, AgentExecutionControl.ActivityOutcome.FAILED, stepSummary.strip());
        persist(current, failed, stepSummary);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failActiveIfOwned(
            UUID runId,
            String ownerUsername,
            String errorCode,
            String stepSummary) {
        if (runId == null || ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("active assistant run identity is required");
        }
        validateSummary(stepSummary);
        String owner = ownerUsername.strip();
        AssistantRun current = repository.find(runId).orElse(null);
        if (current == null) return true;
        if (!current.ownerUsername().equals(owner)) {
            throw new IllegalArgumentException("active assistant run does not exist");
        }
        if (current.state().terminal()) return true;
        if (current.state() == AssistantRunState.RECEIVED) return false;

        // Cancellation and post-work completion take the same budget-row lock. Re-read after acquiring it so the
        // terminal decision is made from the state that won that boundary, not from a stale queue snapshot.
        execution.assertFinalizationAllowed(runId);
        current = repository.find(runId).orElse(null);
        if (current == null) return true;
        if (!current.ownerUsername().equals(owner)) {
            throw new IllegalArgumentException("active assistant run does not exist");
        }
        if (current.state().terminal()) return true;
        if (current.state() == AssistantRunState.RECEIVED) return false;
        AssistantRun failed = current.fail(errorCode, Instant.now(clock));
        execution.stopRunning(runId, AgentExecutionControl.ActivityOutcome.FAILED, stepSummary.strip());
        persist(current, failed, stepSummary);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot advance(
            UUID runId,
            long expectedRevision,
            AssistantRunState nextState,
            String stepSummary) {
        AssistantRun current = require(runId, expectedRevision);
        AssistantRun changed = current.advance(nextState, Instant.now(clock));
        execution.assertStepAllowed(runId, changed.revision());
        persist(current, changed, stepSummary);
        return snapshot(changed);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot advanceAfterWork(
            UUID runId,
            long expectedRevision,
            AssistantRunState nextState,
            String stepSummary) {
        execution.assertFinalizationAllowed(runId);
        AssistantRun current = require(runId, expectedRevision);
        if (current.mode() != AssistantRunMode.TEACHING
                && current.mode() != AssistantRunMode.QUESTION_ANSWER) {
            throw new IllegalArgumentException(
                    "post-work finalization is only available to teaching and question-answer runs");
        }
        AssistantRun changed = current.advance(nextState, Instant.now(clock));
        persist(current, changed, stepSummary);
        return snapshot(changed);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot fail(UUID runId, long expectedRevision, String errorCode, String stepSummary) {
        AssistantRun current = require(runId, expectedRevision);
        AssistantRun changed = current.fail(errorCode, Instant.now(clock));
        execution.stopRunning(runId, AgentExecutionControl.ActivityOutcome.FAILED, "Work stopped safely");
        persist(current, changed, stepSummary);
        return snapshot(changed);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunDetails> findOwned(UUID runId, String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return Optional.empty();
        }
        return repository.find(runId)
                .filter(run -> run.ownerUsername().equals(ownerUsername))
                .map(run -> new RunDetails(
                        snapshot(run), repository.steps(runId), execution.budget(runId), execution.activities(runId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunDetails> findForAdministrativeAudit(UUID runId) {
        if (runId == null) return Optional.empty();
        return repository.find(runId).map(run -> details(run, execution.activities(run.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunDetails> findLatestOwned(
            AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        return latestOwnedRun(mode, subjectId, ownerUsername)
                .map(run -> details(run, execution.activities(run.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunDetails> findLatestOwned(
            AssistantRunMode mode,
            UUID subjectId,
            String ownerUsername,
            UUID activityRunId,
            long afterActivitySequence) {
        if (afterActivitySequence < 0) {
            throw new IllegalArgumentException("activity sequence cursor is invalid");
        }
        return latestOwnedRun(mode, subjectId, ownerUsername)
                .map(run -> details(
                        run,
                        execution.activitiesAfter(
                                run.id(), run.id().equals(activityRunId) ? afterActivitySequence : 0)));
    }

    private Optional<AssistantRun> latestOwnedRun(
            AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        if (mode == null || subjectId == null || ownerUsername == null || ownerUsername.isBlank()) {
            return Optional.empty();
        }
        return repository.findLatest(mode, subjectId, ownerUsername.strip());
    }

    private RunDetails details(
            AssistantRun run, List<AgentExecutionControl.ActivitySnapshot> activities) {
        return new RunDetails(
                snapshot(run), repository.steps(run.id()), execution.budget(run.id()), activities);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunSnapshot> findActiveOwned(AssistantRunMode mode, String ownerUsername) {
        if (mode == null || ownerUsername == null || ownerUsername.isBlank()) {
            return List.of();
        }
        return repository.findNonTerminalOwned(mode, ownerUsername.strip()).stream()
                .map(this::snapshot)
                .toList();
    }

    @Override
    @Transactional
    public int failInterrupted(AssistantRunMode mode) {
        List<AssistantRun> interrupted = repository.findNonTerminal(mode);
        for (AssistantRun current : interrupted) {
            AssistantRun failed = current.fail("APPLICATION_RESTARTED", Instant.now(clock));
            execution.stopRunning(current.id(), AgentExecutionControl.ActivityOutcome.FAILED, "Work stopped after restart");
            persist(current, failed, "Generation was interrupted by an application restart");
        }
        return interrupted.size();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCancellation(UUID runId, String ownerUsername) {
        if (!execution.requestCancellationIfActive(runId, ownerUsername)) return;
        AssistantRun current = repository.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("active assistant run does not exist"));
        if (current.state().terminal()) return;
        AssistantRun cancelled = current.fail("AGENT_CANCELLED", Instant.now(clock));
        execution.stopRunning(runId, AgentExecutionControl.ActivityOutcome.REJECTED, "Work stopped by the user");
        persist(current, cancelled, "Cancellation requested by run owner");
    }

    @Override
    @Transactional
    public void deleteOwned(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        if (mode == null || subjectId == null || ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("assistant run deletion scope is invalid");
        }
        latestOwnedRun(mode, subjectId, ownerUsername.strip())
                .filter(run -> !run.state().terminal())
                .ifPresent(run -> requestCancellation(run.id(), ownerUsername.strip()));
        repository.deleteBySubject(mode, subjectId, ownerUsername.strip());
    }

    private AssistantRun require(UUID runId, long expectedRevision) {
        AssistantRun current = repository.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("assistant run does not exist"));
        if (current.revision() != expectedRevision) {
            throw new AssistantRunVersionConflictException();
        }
        return current;
    }

    private BudgetLimits limitsFor(AssistantRunMode mode) {
        return switch (mode) {
            case TEACHING, TEACHING_PREPARATION -> teachingLimits;
            case VISUAL_ENRICHMENT -> visualEnrichmentLimits;
            case QUESTION_ANSWER -> answerLimits;
        };
    }

    private BudgetLimits limitsFor(AssistantRunMode mode, WorkloadDemand workloadDemand) {
        BudgetLimits configured = limitsFor(mode);
        if (workloadDemand == null) return configured;
        if (mode != AssistantRunMode.TEACHING && mode != AssistantRunMode.TEACHING_PREPARATION) {
            throw new IllegalArgumentException("a workload demand is only available to teaching runs");
        }
        return new BudgetLimits(
                workloadAwareTeachingTokens(configured, workloadDemand),
                workloadAwareTeachingTimeout(configured, workloadDemand));
    }

    private int workloadAwareTeachingTokens(BudgetLimits configured, WorkloadDemand workloadDemand) {
        if (workloadDemand.estimatedModelCalls() <= teachingModelCallCapacityBaseline) {
            return configured.maxTokens();
        }
        try {
            long numerator = Math.multiplyExact(
                    (long) configured.maxTokens(), workloadDemand.estimatedModelCalls());
            long projected = numerator / teachingModelCallCapacityBaseline;
            if (numerator % teachingModelCallCapacityBaseline != 0) projected++;
            return (int) Math.min(projected, teachingMaxWorkloadTokens);
        } catch (ArithmeticException overflow) {
            return teachingMaxWorkloadTokens;
        }
    }

    private Duration workloadAwareTeachingTimeout(BudgetLimits configured, WorkloadDemand workloadDemand) {
        if (workloadDemand.estimatedModelCalls() <= teachingModelCallCapacityBaseline) {
            return configured.timeout();
        }
        Duration projected;
        try {
            // The model-call estimate sizes active-work capacity; it is never persisted as a call limit. Recovery may
            // exceed the estimate while the run still has wall time and tokens.
            projected = configured.timeout()
                    .multipliedBy(workloadDemand.estimatedModelCalls())
                    .dividedBy(teachingModelCallCapacityBaseline);
        } catch (ArithmeticException overflow) {
            return teachingMaxWorkloadTimeout;
        }
        return projected.compareTo(teachingMaxWorkloadTimeout) > 0
                ? teachingMaxWorkloadTimeout
                : projected;
    }

    private void persist(AssistantRun current, AssistantRun changed, String summary) {
        validateSummary(summary);
        if (!repository.update(current, changed, summary.strip())) {
            throw new AssistantRunVersionConflictException();
        }
    }

    private void validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("assistant run step summary is invalid");
        }
    }

    private RunSnapshot snapshot(AssistantRun run) {
        return new RunSnapshot(
                run.id(), run.mode(), run.subjectId(), run.ownerUsername(), run.state(), run.revision(),
                run.createdAt(), run.updatedAt(), run.completedAt(), run.lastErrorCode());
    }
}
