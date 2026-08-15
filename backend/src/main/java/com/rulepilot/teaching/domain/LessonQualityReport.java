package com.rulepilot.teaching.domain;

import java.util.List;

public record LessonQualityReport(OverallStatus status, int score, List<QualityCheck> checks) {

    public LessonQualityReport {
        if (status == null || score < 0 || score > 100) {
            throw new IllegalArgumentException("quality status and score are required");
        }
        checks = List.copyOf(checks);
    }

    public enum OverallStatus {
        READY,
        NEEDS_REVIEW,
        BLOCKED
    }

    public enum CheckType {
        REQUIRED_SECTION_COVERAGE,
        SOURCE_RULE_GROUP_COVERAGE,
        SOURCE_AVAILABILITY,
        CITATION_SUPPORT,
        SETUP_EXECUTABILITY,
        END_AND_SCORING_COMPLETENESS,
        EXPANSION_SCOPE
    }

    public enum CheckStatus {
        PASS,
        FAIL,
        NOT_EVALUATED
    }

    public record QualityCheck(CheckType type, CheckStatus status, String summary, String detail) {
        public QualityCheck {
            if (type == null || status == null || summary == null || summary.isBlank() || detail == null) {
                throw new IllegalArgumentException("quality check fields are required");
            }
        }
    }
}
