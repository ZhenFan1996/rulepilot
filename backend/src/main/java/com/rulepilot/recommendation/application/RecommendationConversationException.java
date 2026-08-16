package com.rulepilot.recommendation.application;

/** A recoverable protocol conflict in a persisted recommendation conversation. */
public final class RecommendationConversationException extends RuntimeException {

    private final Code code;

    public RecommendationConversationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        NOT_FOUND,
        REVISION_CONFLICT,
        TURN_ID_REUSED,
        TURN_IN_PROGRESS,
        CONCURRENT_TURN
    }
}
