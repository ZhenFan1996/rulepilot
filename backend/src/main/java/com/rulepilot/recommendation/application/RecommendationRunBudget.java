package com.rulepilot.recommendation.application;

/**
 * Resource owner for one synchronous recommendation turn.
 *
 * <p>Step and tool ceilings are derived from the token envelope so they remain hard safety boundaries without
 * encoding a product-authored workflow length. Model calls are observational; each call is admitted only when its
 * complete prompt and at least one output token still fit.</p>
 */
final class RecommendationRunBudget {

    enum StopReason {
        STEP_BUDGET,
        TOOL_BUDGET,
        TOKEN_BUDGET
    }

    private final int maxTokens;
    private final int maxSteps;
    private final int maxToolCalls;
    private int usedTokens;
    private int usedSteps;
    private int usedToolCalls;
    private int reservedModelInputTokens;

    RecommendationRunBudget(int maxTokens) {
        if (maxTokens < 1) throw new IllegalArgumentException("recommendation token budget must be positive");
        this.maxTokens = maxTokens;
        maxSteps = maxTokens;
        maxToolCalls = maxTokens;
    }

    StopReason beginModelStep(int estimatedInputTokens) {
        if (estimatedInputTokens < 1) {
            throw new IllegalArgumentException("recommendation model input estimate must be positive");
        }
        if (usedSteps >= maxSteps) return StopReason.STEP_BUDGET;
        if (wouldExceed((long) estimatedInputTokens + 1L)) return StopReason.TOKEN_BUDGET;
        usedSteps++;
        usedTokens += estimatedInputTokens;
        reservedModelInputTokens = estimatedInputTokens;
        return null;
    }

    StopReason completeModel(int promptTokens, int completionTokens, int fallbackOutputTokens) {
        if (promptTokens < 0 || completionTokens < 0 || fallbackOutputTokens < 0) {
            throw new IllegalArgumentException("recommendation model token usage must be non-negative");
        }
        long promptRemainder = Math.max(0L, (long) promptTokens - reservedModelInputTokens);
        long output = completionTokens > 0 ? completionTokens : fallbackOutputTokens;
        reservedModelInputTokens = 0;
        return charge(promptRemainder + output);
    }

    StopReason beginToolCall(int estimatedInputTokens) {
        if (estimatedInputTokens < 1) {
            throw new IllegalArgumentException("recommendation tool input estimate must be positive");
        }
        if (usedToolCalls >= maxToolCalls) return StopReason.TOOL_BUDGET;
        if (wouldExceed(estimatedInputTokens)) return StopReason.TOKEN_BUDGET;
        usedToolCalls++;
        usedTokens += estimatedInputTokens;
        return null;
    }

    StopReason completeToolCall(int estimatedOutputTokens) {
        if (estimatedOutputTokens < 0) {
            throw new IllegalArgumentException("recommendation tool output estimate must be non-negative");
        }
        return charge(estimatedOutputTokens);
    }

    int remainingTokens() {
        return Math.max(0, maxTokens - usedTokens);
    }

    int usedTokens() {
        return usedTokens;
    }

    static int estimateTokens(String value) {
        if (value == null || value.isEmpty()) return 0;
        return saturatedInt(((long) value.length() + 3L) / 4L);
    }

    static int saturatedAdd(int left, int right) {
        return saturatedInt((long) left + right);
    }

    private StopReason charge(long tokens) {
        if (wouldExceed(tokens)) return StopReason.TOKEN_BUDGET;
        usedTokens += (int) tokens;
        return null;
    }

    private boolean wouldExceed(long tokens) {
        return tokens < 0 || (long) usedTokens + tokens > maxTokens;
    }

    private static int saturatedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }
}
