package com.rulepilot.assistant;

public class RuleAnswerModelTimeoutException extends RuntimeException {

    public RuleAnswerModelTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
