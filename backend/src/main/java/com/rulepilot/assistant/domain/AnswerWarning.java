package com.rulepilot.assistant.domain;

/** A bounded, player-visible qualification on an otherwise evidence-scoped answer. */
public record AnswerWarning(Type type) {

    public AnswerWarning {
        if (type == null) {
            throw new IllegalArgumentException("answer warning type is required");
        }
    }

    public enum Type {
        INDIRECT_CITATION,
        LOW_CONFIDENCE,
        REVIEW_UNRESOLVED,
        REVIEW_UNAVAILABLE
    }
}
