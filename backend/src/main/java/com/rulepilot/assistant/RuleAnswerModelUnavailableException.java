package com.rulepilot.assistant;

/** The configured answer model or its provider could not accept the request. */
public final class RuleAnswerModelUnavailableException extends RuntimeException {

    public RuleAnswerModelUnavailableException(String message) {
        super(message);
    }

    public RuleAnswerModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
