package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.Locale;
import java.util.UUID;

/** Pure cache identity rules for a grounded answer and its bounded player context. */
final class AnswerCacheScopePolicy {

    private AnswerCacheScopePolicy() {}

    static AnswerCacheKey key(
            String policyVersion,
            long ruleDataVersion,
            UnderstoodQuestion question,
            QuestionContext context,
            UUID gameSessionId) {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("answer cache policy version is required");
        }
        String scopedQuestion = scopedQuestion(policyVersion, question, context, gameSessionId);
        return new AnswerCacheKey(
                context.documentVersionId(),
                ruleDataVersion,
                scopedQuestion,
                context.currentLessonSection(),
                context.gamePhase(),
                context.playerCount(),
                context.activeExpansions(),
                context.outputLanguage());
    }

    private static String scopedQuestion(
            String policyVersion, UnderstoodQuestion question, QuestionContext context, UUID gameSessionId) {
        String conversation = context.previousQuestion() == null
                ? question.normalizedQuestion()
                : context.previousQuestion().toLowerCase(Locale.ROOT) + " -> " + question.normalizedQuestion();
        String scoped = policyVersion + ":" + context.outputLanguage().name() + ":" + conversation;
        if (gameSessionId != null) {
            scoped = "LIVE_TABLE:" + scoped;
        }
        return context.learningIntent() == null ? scoped : context.learningIntent().name() + ":" + scoped;
    }
}
