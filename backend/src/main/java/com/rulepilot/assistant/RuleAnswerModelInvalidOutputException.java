package com.rulepilot.assistant;

/** The answer provider replied, but its complete structured response did not satisfy the answer contract. */
public final class RuleAnswerModelInvalidOutputException extends IllegalStateException {

    public RuleAnswerModelInvalidOutputException(String message) {
        super(message);
    }

    public RuleAnswerModelInvalidOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
