package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.Locale;

/** Pure cache identity rules for an answer grounded in one rulebook version. */
final class AnswerCacheScopePolicy {

    private AnswerCacheScopePolicy() {}

    static AnswerCacheKey key(
            String policyVersion,
            long ruleDataVersion,
            UnderstoodQuestion question,
            QuestionContext context) {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("answer cache policy version is required");
        }
        String scopedQuestion = scopedQuestion(policyVersion, question, context);
        return new AnswerCacheKey(
                context.documentVersionId(),
                ruleDataVersion,
                scopedQuestion,
                context.currentLessonSection(),
                context.outputLanguage());
    }

    private static String scopedQuestion(
            String policyVersion, UnderstoodQuestion question, QuestionContext context) {
        String conversation = context.previousQuestion() == null
                ? question.normalizedQuestion()
                : context.previousQuestion().toLowerCase(Locale.ROOT) + " -> " + question.normalizedQuestion();
        String scoped = policyVersion + ":" + context.outputLanguage().name() + ":" + conversation;
        return context.learningIntent() == null ? scoped : context.learningIntent().name() + ":" + scoped;
    }
}
