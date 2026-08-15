package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.retrieval.AnswerRetrievalInvocations;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Keeps retrieval tool calls inside the owning Answer Agent's existing audit and budget ledger. */
final class AuditedAnswerRetrievalInvocations implements AnswerRetrievalInvocations {

    private final AuditedAgentInvocations delegate;

    AuditedAnswerRetrievalInvocations(AuditedAgentInvocations delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> T invoke(
            UUID runId,
            String operation,
            int estimatedInputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator) {
        return delegate.invoke(
                runId,
                ActivityType.TOOL,
                operation,
                estimatedInputTokens,
                successSummary,
                invocation,
                outputTokenEstimator);
    }

    @Override
    public boolean executionStopped(RuntimeException failure) {
        return failure instanceof AgentExecutionStoppedException;
    }
}
