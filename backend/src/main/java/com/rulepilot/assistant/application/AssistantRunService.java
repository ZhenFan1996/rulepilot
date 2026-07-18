package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.domain.AssistantRun;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AssistantRunService implements AssistantRuns {

    private final AssistantRunRepository repository;
    private final Clock clock = Clock.systemUTC();

    public AssistantRunService(AssistantRunRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        AssistantRun run = AssistantRun.start(mode, subjectId, ownerUsername, Instant.now(clock));
        repository.insert(run, "Run received");
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
                .map(run -> new RunDetails(snapshot(run), repository.steps(runId)));
    }

    private AssistantRun require(UUID runId, long expectedRevision) {
        AssistantRun current = repository.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("assistant run does not exist"));
        if (current.revision() != expectedRevision) {
            throw new AssistantRunVersionConflictException();
        }
        return current;
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
