package com.rulepilot.ruling.application;

public class RulingVersionConflictException extends RuntimeException {

    private final long currentVersion;

    public RulingVersionConflictException(long currentVersion) {
        super("confirmed ruling was changed by another request");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
