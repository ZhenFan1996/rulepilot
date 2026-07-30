package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.assistant.domain.LearningIntent;
import java.util.UUID;

public interface QuestionUnderstanding {

    UnderstoodQuestion understand(String question, QuestionContext context);

    record QuestionContext(
            UUID documentVersionId,
            String currentLessonSection,
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage) {

        public QuestionContext(UUID documentVersionId, String currentLessonSection) {
            this(documentVersionId, currentLessonSection, null, null, PlayerLocale.ZH_CN);
        }

        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String previousQuestion,
                LearningIntent learningIntent) {
            this(documentVersionId, currentLessonSection, previousQuestion, learningIntent, PlayerLocale.ZH_CN);
        }

        /**
         * Compatibility boundary for callers written before live-table state was removed from rule answering.
         * The three table-state arguments are deliberately ignored.
         */
        @Deprecated(forRemoval = true)
        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String ignoredGamePhase,
                Integer ignoredPlayerCount,
                java.util.Set<UUID> ignoredActiveExpansions) {
            this(documentVersionId, currentLessonSection, null, null, PlayerLocale.ZH_CN);
        }

        /** @see #QuestionContext(UUID, String, String, Integer, java.util.Set) */
        @Deprecated(forRemoval = true)
        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String ignoredGamePhase,
                Integer ignoredPlayerCount,
                java.util.Set<UUID> ignoredActiveExpansions,
                String previousQuestion) {
            this(documentVersionId, currentLessonSection, previousQuestion, null, PlayerLocale.ZH_CN);
        }

        /** @see #QuestionContext(UUID, String, String, Integer, java.util.Set) */
        @Deprecated(forRemoval = true)
        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String ignoredGamePhase,
                Integer ignoredPlayerCount,
                java.util.Set<UUID> ignoredActiveExpansions,
                String previousQuestion,
                LearningIntent learningIntent) {
            this(documentVersionId, currentLessonSection, previousQuestion, learningIntent, PlayerLocale.ZH_CN);
        }

        /** @see #QuestionContext(UUID, String, String, Integer, java.util.Set) */
        @Deprecated(forRemoval = true)
        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String ignoredGamePhase,
                Integer ignoredPlayerCount,
                java.util.Set<UUID> ignoredActiveExpansions,
                String previousQuestion,
                LearningIntent learningIntent,
                PlayerLocale outputLanguage) {
            this(documentVersionId, currentLessonSection, previousQuestion, learningIntent, outputLanguage);
        }

        public QuestionContext {
            if (documentVersionId == null) {
                throw new IllegalArgumentException("question context is invalid");
            }
            currentLessonSection = normalize(currentLessonSection);
            previousQuestion = normalize(previousQuestion);
            if (previousQuestion != null && previousQuestion.length() > 800) {
                throw new IllegalArgumentException("previous question is too long");
            }
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
