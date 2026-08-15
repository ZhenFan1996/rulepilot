package com.rulepilot.recommendation;

/** A recoverable validation failure at the player-authored conversation boundary. */
public final class RecommendationConversationInputException extends IllegalArgumentException {

    private final Code code;
    private final int limit;
    private final int actual;

    RecommendationConversationInputException(Code code, int limit, int actual) {
        super("recommendation conversation text is invalid");
        this.code = code;
        this.limit = limit;
        this.actual = actual;
    }

    public Code code() {
        return code;
    }

    public int limit() {
        return limit;
    }

    public int actual() {
        return actual;
    }

    public enum Code {
        MESSAGE_TOO_LONG
    }
}
