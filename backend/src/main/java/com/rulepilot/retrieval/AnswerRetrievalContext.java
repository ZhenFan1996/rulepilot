package com.rulepilot.retrieval;

import java.util.Set;
import java.util.UUID;

/** Version scope and prior player context accepted by answer evidence retrieval. */
public record AnswerRetrievalContext(
        UUID documentVersionId,
        String previousQuestion,
        LearningIntent learningIntent,
        Set<Integer> allowedEvidencePages) {

    public AnswerRetrievalContext {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("answer retrieval context is invalid");
        }
        if (allowedEvidencePages != null) {
            if (allowedEvidencePages.isEmpty()
                    || allowedEvidencePages.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("answer retrieval page scope is invalid");
            }
            allowedEvidencePages = Set.copyOf(allowedEvidencePages);
        }
    }

    public AnswerRetrievalContext(UUID documentVersionId) {
        this(documentVersionId, null, null, null);
    }

    public AnswerRetrievalContext(
            UUID documentVersionId,
            String previousQuestion,
            LearningIntent learningIntent) {
        this(documentVersionId, previousQuestion, learningIntent, null);
    }

    public enum LearningIntent {
        SIMPLIFY,
        EXAMPLE,
        DEFINE,
        WHY,
        EXCEPTIONS,
        SOURCE,
        VERIFY
    }
}
