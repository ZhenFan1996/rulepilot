package com.rulepilot.retrieval.domain;

import com.rulepilot.retrieval.HybridRuleSearch.SourceAvailability;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample.RelevantEvidence;
import java.util.List;
import java.util.UUID;

public record RetrievalEvaluationReport(
        String evaluationSet,
        String sourceSha256,
        UUID documentVersionId,
        int sampleCount,
        int hitCount,
        double recallAt5,
        double meanReciprocalRank,
        double averageLatencyMillis,
        double p95LatencyMillis,
        double maximumLatencyMillis,
        List<SampleResult> sampleResults,
        List<RetrievalError> errors) {

    public RetrievalEvaluationReport {
        sampleResults = List.copyOf(sampleResults);
        errors = List.copyOf(errors);
    }

    public record SampleResult(
            String sampleId,
            String question,
            List<RelevantEvidence> relevantEvidence,
            int relevantRank,
            double latencyMillis,
            SourceAvailability sourceAvailability,
            List<RetrievedCandidate> retrieved) {

        public SampleResult {
            if (sourceAvailability == null) {
                throw new IllegalArgumentException("retrieval source availability is required");
            }
            relevantEvidence = List.copyOf(relevantEvidence);
            retrieved = List.copyOf(retrieved);
        }
    }

    public record RetrievalError(
            String sampleId,
            String question,
            List<RelevantEvidence> relevantEvidence,
            SourceAvailability sourceAvailability,
            List<RetrievedCandidate> retrieved) {

        public RetrievalError {
            if (sourceAvailability == null) {
                throw new IllegalArgumentException("retrieval source availability is required");
            }
            relevantEvidence = List.copyOf(relevantEvidence);
            retrieved = List.copyOf(retrieved);
        }
    }

    public record RetrievedCandidate(UUID chunkId, String sectionType, String heading, int pageFrom, int pageTo) {}
}
