package com.rulepilot.assistant;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public interface AuditedAgentInvocations {

    <T> T invoke(
            UUID runId,
            AgentExecutionControl.ActivityType type,
            String operation,
            int estimatedInputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator);

    default void record(
            UUID runId,
            AgentExecutionControl.ActivityType type,
            String operation,
            AgentExecutionControl.ActivityOutcome outcome,
            String summary) {}

    default void stopRunning(
            UUID runId, AgentExecutionControl.ActivityOutcome outcome, String summary) {}

    /**
     * Settles one bounded invocation without changing unrelated parallel work in the same run.
     */
    default void stopRunning(
            UUID runId, String operation, AgentExecutionControl.ActivityOutcome outcome, String summary) {
        stopRunning(runId, outcome, summary);
    }
}
