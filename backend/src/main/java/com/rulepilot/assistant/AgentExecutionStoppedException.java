package com.rulepilot.assistant;

public class AgentExecutionStoppedException extends RuntimeException {

    private final StopReason reason;

    public AgentExecutionStoppedException(StopReason reason) {
        super("assistant execution stopped: " + reason.name());
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
        TIMEOUT,
        CANCELLED
    }
}
