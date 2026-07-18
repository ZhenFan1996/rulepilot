package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UnderstoodQuestion(
        UUID documentVersionId,
        String originalQuestion,
        String normalizedQuestion,
        QuestionType type,
        List<String> terms,
        Set<MissingQuestionContext> missingContext,
        String currentLessonSection) {

    public UnderstoodQuestion {
        if (documentVersionId == null
                || originalQuestion == null
                || originalQuestion.isBlank()
                || normalizedQuestion == null
                || normalizedQuestion.isBlank()
                || type == null
                || terms == null
                || missingContext == null) {
            throw new IllegalArgumentException("understood question is invalid");
        }
        terms = List.copyOf(terms);
        missingContext = Set.copyOf(missingContext);
    }

    public boolean needsClarification() {
        return !missingContext.isEmpty();
    }
}
