package com.rulepilot.retrieval.domain;

import java.util.Set;

public record RetrievalEvaluationSample(String id, String question, Set<String> expectedSectionTypes) {

    public RetrievalEvaluationSample {
        if (id == null || id.isBlank() || question == null || question.isBlank()
                || expectedSectionTypes == null
                || expectedSectionTypes.isEmpty()
                || expectedSectionTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
            throw new IllegalArgumentException("retrieval evaluation sample is invalid");
        }
        id = id.strip();
        question = question.strip();
        expectedSectionTypes = expectedSectionTypes.stream()
                .map(String::strip)
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
