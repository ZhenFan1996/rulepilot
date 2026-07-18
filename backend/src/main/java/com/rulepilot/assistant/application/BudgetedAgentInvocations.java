package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BudgetedAgentInvocations implements AuditedAgentInvocations {

    private final AgentExecutionControl execution;

    public BudgetedAgentInvocations(AgentExecutionControl execution) {
        this.execution = execution;
    }

    @Override
    public <T> T invoke(
            UUID runId,
            ActivityType type,
            String operation,
            int estimatedInputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator) {
        if (runId == null || invocation == null || outputTokenEstimator == null) {
            throw new IllegalArgumentException("audited agent invocation is invalid");
        }
        var reservation = execution.reserve(runId, type, operation, estimatedInputTokens);
        long started = System.nanoTime();
        T result;
        try {
            result = invocation.get();
        } catch (RuntimeException exception) {
            try {
                execution.complete(
                        reservation, ActivityOutcome.FAILED, 0, elapsedMillis(started),
                        operation + " failed safely");
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        }
        execution.complete(
                reservation,
                ActivityOutcome.SUCCEEDED,
                Math.max(0, outputTokenEstimator.applyAsInt(result)),
                elapsedMillis(started),
                successSummary);
        return result;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
