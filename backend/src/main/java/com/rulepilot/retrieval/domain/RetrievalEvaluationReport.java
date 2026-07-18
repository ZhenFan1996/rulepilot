package com.rulepilot.retrieval.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RetrievalEvaluationReport(
        String evaluationSet,
        UUID documentVersionId,
        int sampleCount,
        int hitCount,
        double recallAt5,
        double meanReciprocalRank,
        double averageLatencyMillis,
        double p95LatencyMillis,
        List<RetrievalError> errors) {

    public RetrievalEvaluationReport {
        errors = List.copyOf(errors);
    }

    public record RetrievalError(
            String sampleId,
            String question,
            Set<String> expectedSectionTypes,
            List<RetrievedCandidate> retrieved) {

        public RetrievalError {
            expectedSectionTypes = Set.copyOf(expectedSectionTypes);
            retrieved = List.copyOf(retrieved);
        }
    }

    public record RetrievedCandidate(String sectionType, String heading, int pageFrom, int pageTo) {}
}
