package com.rulepilot.retrieval;

import java.util.List;

/** Answer-facing question facts that retrieval may use without depending on assistant orchestration types. */
public record AnswerRetrievalQuestion(
        String normalizedQuestion,
        QuestionType type,
        List<String> terms) {

    public AnswerRetrievalQuestion {
        if (normalizedQuestion == null || normalizedQuestion.isBlank() || type == null || terms == null) {
            throw new IllegalArgumentException("answer retrieval question is invalid");
        }
        terms = List.copyOf(terms);
    }

    public enum QuestionType {
        LESSON_STEP_FOLLOW_UP,
        RULE_QUERY,
        SITUATION_QUERY
    }
}
