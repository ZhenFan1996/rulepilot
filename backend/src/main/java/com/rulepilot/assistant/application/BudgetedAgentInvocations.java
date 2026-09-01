package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.modelconfig.ModelAccountQuotaFailures;
import java.util.UUID;
import java.util.function.Function;
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
    public void record(
            UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
        execution.record(runId, type, operation, outcome, summary);
    }

    @Override
    public void stopRunning(UUID runId, ActivityOutcome outcome, String summary) {
        if (runId != null) execution.stopRunning(runId, outcome, summary);
    }

    @Override
    public void stopRunning(UUID runId, String operation, ActivityOutcome outcome, String summary) {
        if (runId != null) execution.stopRunning(runId, operation, outcome, summary);
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
        return invoke(
                runId,
                type,
                operation,
                estimatedInputTokens,
                successSummary,
                invocation,
                outputTokenEstimator,
                ignored -> successSummary);
    }

    @Override
    public <T> T invoke(
            UUID runId,
            ActivityType type,
            String operation,
            int estimatedInputTokens,
            String fallbackSuccessSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator,
            Function<T, String> successSummary) {
        if (runId == null || invocation == null || outputTokenEstimator == null || successSummary == null) {
            throw new IllegalArgumentException("audited agent invocation is invalid");
        }
        var reservation = execution.reserve(runId, type, operation, estimatedInputTokens);
        long started = System.nanoTime();
        T result;
        try {
            result = invocation.get();
        } catch (RuntimeException exception) {
            if (ModelAccountQuotaFailures.find(exception) != null) {
                try {
                    execution.complete(
                            reservation,
                            ActivityOutcome.REJECTED,
                            0,
                            elapsedMillis(started),
                            "Model account quota exhausted");
                } catch (RuntimeException auditFailure) {
                    exception.addSuppressed(auditFailure);
                }
                throw new AgentExecutionStoppedException(StopReason.ACCOUNT_QUOTA, exception);
            }
            try {
                execution.complete(
                        reservation, ActivityOutcome.FAILED, 0, elapsedMillis(started),
                        operation + " failed safely");
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        }
        String resolvedSuccessSummary = successSummary.apply(result);
        if (resolvedSuccessSummary == null || resolvedSuccessSummary.isBlank()) {
            resolvedSuccessSummary = fallbackSuccessSummary;
        }
        execution.complete(
                reservation,
                ActivityOutcome.SUCCEEDED,
                Math.max(0, outputTokenEstimator.applyAsInt(result)),
                elapsedMillis(started),
                resolvedSuccessSummary);
        return result;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
