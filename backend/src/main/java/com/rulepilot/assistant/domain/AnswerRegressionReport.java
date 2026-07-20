package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record AnswerRegressionReport(
        String evaluationSet,
        UUID documentVersionId,
        int caseCount,
        int passedCount,
        long totalLatencyMillis,
        List<CaseResult> cases) {

    public AnswerRegressionReport {
        cases = List.copyOf(cases);
    }

    public boolean isPassed() {
        return caseCount == passedCount;
    }

    public record CaseResult(
            String caseId,
            boolean passed,
            AnswerStatus actualStatus,
            List<Integer> citedPages,
            List<String> failures,
            long latencyMillis,
            UUID assistantRunId) {

        public CaseResult {
            citedPages = List.copyOf(citedPages);
            failures = List.copyOf(failures);
        }
    }
}
