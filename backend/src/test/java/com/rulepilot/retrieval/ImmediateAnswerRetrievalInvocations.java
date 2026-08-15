package com.rulepilot.retrieval;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

final class ImmediateAnswerRetrievalInvocations implements AnswerRetrievalInvocations {

    @Override
    public <T> T invoke(
            UUID runId,
            String operation,
            int estimatedInputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator) {
        return invocation.get();
    }

    @Override
    public boolean executionStopped(RuntimeException failure) {
        return false;
    }
}
