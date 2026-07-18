package com.rulepilot.assistant.application;

public class RuleAnswerRateLimitUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public RuleAnswerRateLimitUnavailableException(long retryAfterSeconds, Throwable cause) {
        super("rule answer rate limiting is unavailable", cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
