package com.rulepilot.retrieval;

import java.util.UUID;

/** Version scope and prior player context accepted by answer evidence retrieval. */
public record AnswerRetrievalContext(
        UUID documentVersionId,
        String previousQuestion,
        LearningIntent learningIntent) {

    public AnswerRetrievalContext {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("answer retrieval context is invalid");
        }
    }

    public AnswerRetrievalContext(UUID documentVersionId) {
        this(documentVersionId, null, null);
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
