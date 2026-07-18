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
}
