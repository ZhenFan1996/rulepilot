package com.rulepilot.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentExecutionControl {

    enum ActivityType {
        TOOL,
        MODEL,
        CRITIC,
        VALIDATION
    }

    enum ActivityOutcome {
        RUNNING,
        SUCCEEDED,
        FAILED,
        REJECTED
    }

    record BudgetLimits(int maxSteps, int maxToolCalls, int maxModelCalls, int maxTokens, Duration timeout) {
        public BudgetLimits {
            if (maxSteps < 1 || maxToolCalls < 1 || maxModelCalls < 1 || maxTokens < 1
                    || timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("agent budget limits are invalid");
            }
        }
    }

    record BudgetSnapshot(
            int maxSteps,
            int maxToolCalls,
            int maxModelCalls,
            int maxTokens,
            int usedToolCalls,
            int usedModelCalls,
            int usedTokens,
            Instant deadlineAt,
            Instant cancellationRequestedAt) {}

    record ActivitySnapshot(
            long sequence,
            ActivityType type,
            String operation,
            ActivityOutcome outcome,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            long latencyMs,
            String summary,
            Instant occurredAt) {}

    void initialize(UUID runId, BudgetLimits limits, Instant startedAt);

    void assertStepAllowed(UUID runId, long nextStep);

    InvocationReservation reserve(UUID runId, ActivityType type, String operation, int estimatedInputTokens);

    void complete(
            InvocationReservation reservation,
            ActivityOutcome outcome,
            int estimatedOutputTokens,
            long latencyMs,
            String summary);

    void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary);

    void stopRunning(UUID runId, ActivityOutcome outcome, String summary);

    void requestCancellation(UUID runId, String ownerUsername);

    BudgetSnapshot budget(UUID runId);

    List<ActivitySnapshot> activities(UUID runId);

    record InvocationReservation(UUID id, UUID runId, ActivityType type, String operation, int inputTokens) {}
}
