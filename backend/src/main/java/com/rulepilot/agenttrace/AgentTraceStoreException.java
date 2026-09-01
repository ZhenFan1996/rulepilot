package com.rulepilot.agenttrace;

final class AgentTraceStoreException extends RuntimeException {

    enum Reason {
        UNAVAILABLE,
        NOT_FOUND,
        SEALED,
        EXPIRED,
        CAP_REACHED,
        OWNER_QUOTA_REACHED,
        CORRUPT
    }

    private final Reason reason;

    AgentTraceStoreException(Reason reason) {
        super(reason == null ? "agent trace store failure" : reason.name());
        if (reason == null) throw new IllegalArgumentException("agent trace store reason is required");
        this.reason = reason;
    }

    AgentTraceStoreException(Reason reason, Throwable cause) {
        super(reason == null ? "agent trace store failure" : reason.name(), cause);
        if (reason == null) throw new IllegalArgumentException("agent trace store reason is required");
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
