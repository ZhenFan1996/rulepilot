package com.rulepilot.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantRuns {

    RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername);

    RunSnapshot advance(UUID runId, long expectedRevision, AssistantRunState nextState, String stepSummary);

    RunSnapshot fail(UUID runId, long expectedRevision, String errorCode, String stepSummary);

    void requestCancellation(UUID runId, String ownerUsername);

    Optional<RunDetails> findOwned(UUID runId, String ownerUsername);

    record RunSnapshot(
            UUID id,
            AssistantRunMode mode,
            UUID subjectId,
            String ownerUsername,
            AssistantRunState state,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String lastErrorCode) {}

    record StepSnapshot(
            long sequence,
            AssistantRunState fromState,
            AssistantRunState toState,
            String summary,
            Instant occurredAt) {}

    record RunDetails(
            RunSnapshot run,
            List<StepSnapshot> steps,
            AgentExecutionControl.BudgetSnapshot budget,
            List<AgentExecutionControl.ActivitySnapshot> activities) {
        public RunDetails {
            steps = List.copyOf(steps);
            activities = List.copyOf(activities);
        }
    }
}
