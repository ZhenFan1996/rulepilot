package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.shared.AsyncContextPropagation;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Interrupts one provider or tool call at the application-owned run deadline. */
@Component
@Profile("!test")
final class AgentInvocationDeadline {

    private static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofMillis(100);

    private final AgentExecutionControl execution;
    private final ExecutorService calls;
    private final Duration cancellationPollInterval;

    AgentInvocationDeadline(AgentExecutionControl execution) {
        this(
                execution,
                AsyncContextPropagation.executorService(Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("answer-bounded-call-", 0).factory())),
                CANCELLATION_POLL_INTERVAL);
    }

    AgentInvocationDeadline(
            AgentExecutionControl execution,
            ExecutorService calls,
            Duration cancellationPollInterval) {
        if (execution == null || calls == null || cancellationPollInterval == null
                || cancellationPollInterval.isZero() || cancellationPollInterval.isNegative()) {
            throw new IllegalArgumentException("agent invocation deadline dependencies are invalid");
        }
        this.execution = execution;
        this.calls = calls;
        this.cancellationPollInterval = cancellationPollInterval;
    }

    private AgentInvocationDeadline() {
        this.execution = null;
        this.calls = null;
        this.cancellationPollInterval = CANCELLATION_POLL_INTERVAL;
    }

    static AgentInvocationDeadline unbounded() {
        return new AgentInvocationDeadline();
    }

    <T> T invoke(UUID runId, Supplier<T> invocation) {
        if (execution == null) return invocation.get();
        BudgetSnapshot budget = execution.budget(runId);
        return invoke(runId, budget.deadlineAt(), invocation);
    }

    <T> T invoke(UUID runId, Instant operationDeadline, Supplier<T> invocation) {
        if (execution == null) return invocation.get();
        if (runId == null || operationDeadline == null || invocation == null) {
            throw new IllegalArgumentException("bounded agent invocation is invalid");
        }
        assertActive(runId, operationDeadline);
        Future<T> pending = calls.submit(invocation::get);
        try {
            while (true) {
                Instant now = Instant.now();
                if (!now.isBefore(operationDeadline)) {
                    throw new AgentExecutionStoppedException(StopReason.TIMEOUT);
                }
                long remainingNanos = Duration.between(now, operationDeadline).toNanos();
                long waitNanos = Math.min(remainingNanos, cancellationPollInterval.toNanos());
                try {
                    T result = pending.get(Math.max(1, waitNanos), TimeUnit.NANOSECONDS);
                    assertActive(runId, operationDeadline);
                    return result;
                } catch (TimeoutException stillRunning) {
                    assertActive(runId, operationDeadline);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionStoppedException(StopReason.CANCELLED);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("bounded agent invocation failed", cause);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private void assertActive(UUID runId, Instant operationDeadline) {
        BudgetSnapshot budget = execution.budget(runId);
        if (budget.cancellationRequestedAt() != null) {
            throw new AgentExecutionStoppedException(StopReason.CANCELLED);
        }
        Instant effectiveDeadline = budget.deadlineAt().isBefore(operationDeadline)
                ? budget.deadlineAt()
                : operationDeadline;
        if (!Instant.now().isBefore(effectiveDeadline)) {
            throw new AgentExecutionStoppedException(StopReason.TIMEOUT);
        }
    }

    @PreDestroy
    void stop() {
        if (calls != null) calls.shutdownNow();
    }
}
