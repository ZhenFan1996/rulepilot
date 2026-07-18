package com.rulepilot.assistant.application;

public class RuleAnswerRateLimitExceededException extends RuntimeException {

    private final Dimension dimension;
    private final long retryAfterSeconds;

    public RuleAnswerRateLimitExceededException(Dimension dimension, long retryAfterSeconds) {
        super("rule answer rate limit exceeded: " + dimension.name());
        this.dimension = dimension;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public Dimension dimension() {
        return dimension;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public enum Dimension {
        USER,
        GAME_SESSION,
        MODEL_PROVIDER
    }
}
