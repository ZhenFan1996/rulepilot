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

    record BudgetLimits(int maxTokens, Duration timeout, boolean tokenLimitEnforced) {
        public BudgetLimits(int maxTokens, Duration timeout) {
            this(maxTokens, timeout, true);
        }

        public static BudgetLimits observationalTokens(int tokenObservationThreshold, Duration timeout) {
            return new BudgetLimits(tokenObservationThreshold, timeout, false);
        }

        public BudgetLimits {
            if (maxTokens < 1 || timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("agent budget limits are invalid");
            }
        }

        /**
         * A step must consume at least one unit of the persisted token envelope. This derived ceiling is a safety
         * budget, not a product-authored workflow length.
         */
        public int maxSteps() {
            return maxTokens;
        }

        /**
         * A tool reservation must consume at least one unit of the persisted token envelope. This keeps tool work
         * bounded without reintroducing a hand-written call count that rejects otherwise valid workflows.
         */
        public int maxToolCalls() {
            return maxTokens;
        }
    }

    record BudgetSnapshot(
            int maxTokens,
            int usedToolCalls,
            int usedModelCalls,
            int usedTokens,
            Instant deadlineAt,
            Instant cancellationRequestedAt,
            boolean tokenLimitEnforced) {
        public BudgetSnapshot(
                int maxTokens,
                int usedToolCalls,
                int usedModelCalls,
                int usedTokens,
                Instant deadlineAt,
                Instant cancellationRequestedAt) {
            this(
                    maxTokens,
                    usedToolCalls,
                    usedModelCalls,
                    usedTokens,
                    deadlineAt,
                    cancellationRequestedAt,
                    true);
        }
    }

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

    /**
     * Starts the wall-clock budget when previously queued work actually acquires its worker lane.
     * The immutable active-work duration was already persisted when the run was enqueued.
     */
    default void activate(UUID runId, Instant startedAt) {}

    /**
     * Idempotently claims queued work for one delivery. Repeating the same activation ID after an ambiguous
     * transaction response must succeed; a different activation ID must lose the claim.
     */
    default void activate(UUID runId, UUID activationId, Instant startedAt) {
        activate(runId, startedAt);
    }

    /**
     * Locks the queued-work admission row and returns true only while no worker or cancellation has claimed it.
     * The caller must keep the surrounding transaction open until the matching run terminal state is persisted.
     */
    default boolean lockUnactivated(UUID runId) {
        return true;
    }

    /** Locks admission when it is still unclaimed or was claimed by this exact delivery. */
    default boolean lockUnactivatedOrOwned(UUID runId, UUID activationId) {
        return lockUnactivated(runId);
    }

    /** Excludes time spent waiting between bounded teaching work units from the active-work deadline. */
    default void excludeQueueWait(UUID runId, Duration queueWait) {}

    /** Allows post-work publication after resource exhaustion, but never after owner cancellation won the lock. */
    default void assertFinalizationAllowed(UUID runId) {}

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

    default void stopRunning(UUID runId, String operation, ActivityOutcome outcome, String summary) {
        stopRunning(runId, outcome, summary);
    }

    void requestCancellation(UUID runId, String ownerUsername);

    /**
     * Linearizes cancellation against final publication. A false result means the run became terminal before the
     * owner acquired the cancellation boundary, so repeating cancellation is already satisfied.
     */
    default boolean requestCancellationIfActive(UUID runId, String ownerUsername) {
        requestCancellation(runId, ownerUsername);
        return true;
    }

    BudgetSnapshot budget(UUID runId);

    List<ActivitySnapshot> activities(UUID runId);

    default List<ActivitySnapshot> activitiesAfter(UUID runId, long afterSequence) {
        if (afterSequence < 0) throw new IllegalArgumentException("activity sequence cursor is invalid");
        return activities(runId).stream().filter(activity -> activity.sequence() > afterSequence).toList();
    }

    record InvocationReservation(UUID id, UUID runId, ActivityType type, String operation, int inputTokens) {}
}
