package com.rulepilot.assistant.domain;

import java.util.List;

public record AnswerRegressionCase(
        String id,
        String question,
        String previousQuestion,
        AnswerStatus expectedStatus,
        List<Integer> requiredPages,
        List<List<String>> requiredTermGroups,
        List<List<String>> requiredEvidenceTermGroups,
        List<String> forbiddenTerms,
        long maxLatencyMillis) {

    public AnswerRegressionCase(
            String id,
            String question,
            String previousQuestion,
            AnswerStatus expectedStatus,
            List<Integer> requiredPages,
            List<List<String>> requiredTermGroups,
            List<String> forbiddenTerms,
            long maxLatencyMillis) {
        this(
                id, question, previousQuestion, expectedStatus, requiredPages, requiredTermGroups,
                List.of(), forbiddenTerms, maxLatencyMillis);
    }

    public AnswerRegressionCase {
        if (id == null || id.isBlank() || question == null || question.isBlank()) {
            throw new IllegalArgumentException("answer regression case requires an id and question");
        }
        requiredPages = List.copyOf(requiredPages);
        requiredTermGroups = requiredTermGroups.stream().map(List::copyOf).toList();
        requiredEvidenceTermGroups = requiredEvidenceTermGroups == null
                ? List.of()
                : requiredEvidenceTermGroups.stream().map(List::copyOf).toList();
        forbiddenTerms = List.copyOf(forbiddenTerms);
        if (expectedStatus == null || maxLatencyMillis < 1
                || requiredEvidenceTermGroups.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("answer regression expectations are incomplete");
        }
    }
}
