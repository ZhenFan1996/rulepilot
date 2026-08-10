package com.rulepilot.document.application;

/** A public source exists, but completing the fetch requires an interactive user session. */
public final class OfficialRulebookSourceAccessException extends RuntimeException {

    private final Reason reason;

    public OfficialRulebookSourceAccessException(Reason reason, String message) {
        super(message);
        if (reason == null) throw new IllegalArgumentException("rulebook source access reason is required");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INTERACTIVE_BROWSER_REQUIRED
    }
}
