package com.rulepilot.retrieval;

import java.util.List;

/** Answer-facing question facts that retrieval may use without depending on assistant orchestration types. */
public record AnswerRetrievalQuestion(
        String currentQuestion,
        String normalizedQuestion,
        QuestionType type,
        List<String> terms) {

    public AnswerRetrievalQuestion {
        if (currentQuestion == null || currentQuestion.isBlank()
                || normalizedQuestion == null || normalizedQuestion.isBlank() || type == null || terms == null) {
            throw new IllegalArgumentException("answer retrieval question is invalid");
        }
        currentQuestion = currentQuestion.strip();
        terms = List.copyOf(terms);
    }

    public AnswerRetrievalQuestion(
            String normalizedQuestion,
            QuestionType type,
            List<String> terms) {
        this(normalizedQuestion, normalizedQuestion, type, terms);
    }

    public enum QuestionType {
        LESSON_STEP_FOLLOW_UP,
        RULE_QUERY,
        SITUATION_QUERY
    }
}
