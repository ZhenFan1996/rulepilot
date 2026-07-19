package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.domain.AssistantRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AssistantRunService implements AssistantRuns {

    private final AssistantRunRepository repository;
    private final AgentExecutionControl execution;
    private final BudgetLimits defaultLimits;
    private final BudgetLimits teachingLimits;
    private final Clock clock = Clock.systemUTC();

    public AssistantRunService(
            AssistantRunRepository repository,
            AgentExecutionControl execution,
            @Value("${rulepilot.agent.max-steps:40}") int maxSteps,
            @Value("${rulepilot.agent.max-tool-calls:24}") int maxToolCalls,
            @Value("${rulepilot.agent.max-model-calls:16}") int maxModelCalls,
            @Value("${rulepilot.agent.max-tokens:24000}") int maxTokens,
            @Value("${rulepilot.agent.timeout:PT2M}") Duration timeout,
            @Value("${rulepilot.teaching.agent.max-tool-calls:72}") int teachingMaxToolCalls,
            @Value("${rulepilot.teaching.agent.max-model-calls:72}") int teachingMaxModelCalls,
            @Value("${rulepilot.teaching.agent.max-tokens:300000}") int teachingMaxTokens,
            @Value("${rulepilot.teaching.agent.timeout:PT30M}") Duration teachingTimeout) {
        this.repository = repository;
        this.execution = execution;
        this.defaultLimits = new BudgetLimits(maxSteps, maxToolCalls, maxModelCalls, maxTokens, timeout);
        this.teachingLimits = new BudgetLimits(
                maxSteps, teachingMaxToolCalls, teachingMaxModelCalls, teachingMaxTokens, teachingTimeout);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        AssistantRun run = AssistantRun.start(mode, subjectId, ownerUsername, Instant.now(clock));
        repository.insert(run, "Run received");
        execution.initialize(run.id(), limitsFor(mode), run.createdAt());
        return snapshot(run);
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
    public RunSnapshot fail(UUID runId, long expectedRevision, String errorCode, String stepSummary) {
        AssistantRun current = require(runId, expectedRevision);
        AssistantRun changed = current.fail(errorCode, Instant.now(clock));
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
    public Optional<RunDetails> findLatestOwned(
            AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        if (mode == null || subjectId == null || ownerUsername == null || ownerUsername.isBlank()) {
            return Optional.empty();
        }
        return repository.findLatest(mode, subjectId, ownerUsername.strip())
                .map(run -> new RunDetails(
                        snapshot(run), repository.steps(run.id()), execution.budget(run.id()),
                        execution.activities(run.id())));
    }

    @Override
    @Transactional
    public int failInterrupted(AssistantRunMode mode) {
        List<AssistantRun> interrupted = repository.findNonTerminal(mode);
        for (AssistantRun current : interrupted) {
            AssistantRun failed = current.fail("APPLICATION_RESTARTED", Instant.now(clock));
            persist(current, failed, "Generation was interrupted by an application restart");
        }
        return interrupted.size();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCancellation(UUID runId, String ownerUsername) {
        execution.requestCancellation(runId, ownerUsername);
        AssistantRun current = repository.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("active assistant run does not exist"));
        AssistantRun cancelled = current.fail("AGENT_CANCELLED", Instant.now(clock));
        persist(current, cancelled, "Cancellation requested by run owner");
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
        return mode == AssistantRunMode.TEACHING ? teachingLimits : defaultLimits;
    }

    private void persist(AssistantRun current, AssistantRun changed, String summary) {
        validateSummary(summary);
        if (!repository.update(current, changed, summary.strip())) {
            throw new AssistantRunVersionConflictException();
        }
    }

    private void validateSummary(String summary) {
        if (summary == null || summary.isBlank() || summary.length() > 240) {
            throw new IllegalArgumentException("assistant run step summary is invalid");
        }
    }

    private RunSnapshot snapshot(AssistantRun run) {
        return new RunSnapshot(
                run.id(), run.mode(), run.subjectId(), run.ownerUsername(), run.state(), run.revision(),
                run.createdAt(), run.updatedAt(), run.completedAt(), run.lastErrorCode());
    }
}
