package com.rulepilot.retrieval;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Audits bounded retrieval work while leaving the owning Agent's budget implementation outside this module. */
public interface AnswerRetrievalInvocations {

    <T> T invoke(
            UUID runId,
            String operation,
            int estimatedInputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokenEstimator);

    boolean executionStopped(RuntimeException failure);
}
