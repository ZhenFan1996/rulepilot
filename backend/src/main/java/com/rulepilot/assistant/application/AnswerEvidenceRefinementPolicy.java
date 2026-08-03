package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.regex.Pattern;

/** Pure, game-independent policy for when deterministic evidence has an explicit coverage gap. */
final class AnswerEvidenceRefinementPolicy {

    private static final Pattern COMPOUND_SEPARATOR = Pattern.compile(
            "[?？!！;；]+|[,，、]+|\\s+(?i:and|or|then|also)\\s+|(?:以及|并且|然后|同时|还是)");

    private AnswerEvidenceRefinementPolicy() {}

    static boolean requiresRefinement(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerEvidenceRetriever.Result deterministic) {
        if (question == null || context == null || deterministic == null
                || deterministic.state() != AnswerEvidenceRetriever.State.READY) {
            return false;
        }
        if (deterministic.evidence().isEmpty()) return true;
        String playerQuestion = question.normalizedQuestion();
        if (AnswerEvidencePolicy.asksForCompleteList(playerQuestion)) return true;
        if (context.previousQuestion() != null && !context.previousQuestion().isBlank()) return true;
        return hasMultipleObligations(playerQuestion);
    }

    static boolean hasMultipleObligations(String playerQuestion) {
        if (playerQuestion == null || playerQuestion.isBlank()) return false;
        return COMPOUND_SEPARATOR.splitAsStream(playerQuestion)
                        .map(String::strip)
                        .filter(part -> part.length() >= 2)
                        .distinct()
                        .limit(3)
                        .count()
                > 1;
    }
}
