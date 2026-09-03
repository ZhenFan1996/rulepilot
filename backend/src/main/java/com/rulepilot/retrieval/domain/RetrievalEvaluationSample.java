package com.rulepilot.retrieval.domain;

import java.util.List;

public record RetrievalEvaluationSample(String id, String question, List<RelevantEvidence> relevantEvidence) {

    public RetrievalEvaluationSample {
        if (id == null || id.isBlank() || question == null || question.isBlank()
                || relevantEvidence == null
                || relevantEvidence.isEmpty()
                || relevantEvidence.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("retrieval evaluation sample is invalid");
        }
        id = id.strip();
        question = question.strip();
        relevantEvidence = List.copyOf(relevantEvidence);
    }

    public record RelevantEvidence(int pageNumber, List<String> requiredPhrases) {
        public RelevantEvidence {
            if (pageNumber < 1 || requiredPhrases == null || requiredPhrases.isEmpty()
                    || requiredPhrases.stream().anyMatch(phrase -> phrase == null || phrase.isBlank())) {
                throw new IllegalArgumentException("retrieval evaluation evidence target is invalid");
            }
            requiredPhrases = requiredPhrases.stream().map(String::strip).distinct().toList();
        }
    }
}
