package com.rulepilot.teaching.domain;

import java.util.List;

public record MediaConsistencyReport(
        ConsistencyStatus status,
        int consistencyPercent,
        List<ConsistencyCheck> checks) {

    public MediaConsistencyReport {
        if (status == null || consistencyPercent < 0 || consistencyPercent > 100) {
            throw new IllegalArgumentException("media consistency summary is invalid");
        }
        checks = List.copyOf(checks);
    }

    public enum ConsistencyStatus {
        CONSISTENT,
        INCONSISTENT
    }

    public enum CheckStatus {
        PASS,
        FAIL
    }

    public record ConsistencyCheck(
            CheckType type,
            CheckStatus status,
            String summary,
            String detail) {

        public ConsistencyCheck {
            if (type == null || status == null || summary == null || summary.isBlank()
                    || detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("media consistency check is invalid");
            }
        }
    }

    public enum CheckType {
        NARRATION_CONTENT,
        NARRATION_CITATIONS,
        VIDEO_SUBTITLES,
        VIDEO_CITATIONS,
        VIDEO_TIMING
    }
}
