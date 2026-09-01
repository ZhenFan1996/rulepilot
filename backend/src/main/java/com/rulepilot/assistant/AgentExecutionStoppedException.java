package com.rulepilot.assistant;

public class AgentExecutionStoppedException extends RuntimeException {

    private final StopReason reason;

    public AgentExecutionStoppedException(StopReason reason) {
        super("assistant execution stopped: " + reason.name());
        this.reason = reason;
    }

    public AgentExecutionStoppedException(StopReason reason, Throwable cause) {
        super("assistant execution stopped: " + reason.name(), cause);
        this.reason = reason;
    }

    public StopReason reason() {
        return reason;
    }

    public enum StopReason {
        STEP_BUDGET,
        TOOL_BUDGET,
        MODEL_BUDGET,
        TOKEN_BUDGET,
        ACCOUNT_QUOTA,
        TIMEOUT,
        CANCELLED
    }
}
