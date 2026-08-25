package com.rulepilot.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantRuns {

    RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername);

    /**
     * Starts a run with the statically countable work of its immutable plan as the run's exact call budget.
     *
     * <p>This execution budget prevents hidden retries and unbounded loops; it is not an account quota or an
     * administrator entitlement. Runtime output size, provider latency, cancellation, and account quota remain
     * independent concerns. Non-production test implementations may keep the legacy behavior.</p>
     */
    default RunSnapshot start(
            AssistantRunMode mode,
            UUID subjectId,
            String ownerUsername,
            WorkloadDemand workloadDemand) {
        return start(mode, subjectId, ownerUsername);
    }

    RunSnapshot advance(UUID runId, long expectedRevision, AssistantRunState nextState, String stepSummary);

    /**
     * Records a state transition after all model and tool work for that transition has already settled.
     *
     * <p>This keeps a usable, persisted result readable when an optional final review used the last
     * available execution budget. It does not authorize further agent work.
     */
    RunSnapshot advanceAfterWork(UUID runId, long expectedRevision, AssistantRunState nextState, String stepSummary);

    RunSnapshot fail(UUID runId, long expectedRevision, String errorCode, String stepSummary);

    void requestCancellation(UUID runId, String ownerUsername);

    void deleteOwned(AssistantRunMode mode, UUID subjectId, String ownerUsername);

    Optional<RunDetails> findOwned(UUID runId, String ownerUsername);

    /** Administrative audit read; authorization is enforced by the /api/admin security boundary. */
    Optional<RunDetails> findForAdministrativeAudit(UUID runId);

    Optional<RunDetails> findLatestOwned(AssistantRunMode mode, UUID subjectId, String ownerUsername);

    Optional<RunDetails> findLatestOwned(
            AssistantRunMode mode,
            UUID subjectId,
            String ownerUsername,
            UUID activityRunId,
            long afterActivitySequence);

    List<RunSnapshot> findActiveOwned(AssistantRunMode mode, String ownerUsername);

    int failInterrupted(AssistantRunMode mode);

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

    record WorkloadDemand(int requiredToolCalls, int requiredModelCalls) {
        public WorkloadDemand {
            if (requiredToolCalls < 0 || requiredModelCalls < 1) {
                throw new IllegalArgumentException("assistant run workload demand is invalid");
            }
        }
    }
}
