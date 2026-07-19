package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.assistant.domain.LearningIntent;
import java.util.Set;
import java.util.UUID;

public interface QuestionUnderstanding {

    UnderstoodQuestion understand(String question, QuestionContext context);

    record QuestionContext(
            UUID documentVersionId,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<UUID> activeExpansions,
            String previousQuestion,
            LearningIntent learningIntent) {

        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String gamePhase,
                Integer playerCount,
                Set<UUID> activeExpansions) {
            this(documentVersionId, currentLessonSection, gamePhase, playerCount, activeExpansions, null, null);
        }

        public QuestionContext(
                UUID documentVersionId,
                String currentLessonSection,
                String gamePhase,
                Integer playerCount,
                Set<UUID> activeExpansions,
                String previousQuestion) {
            this(documentVersionId, currentLessonSection, gamePhase, playerCount, activeExpansions, previousQuestion, null);
        }

        public QuestionContext {
            if (documentVersionId == null || playerCount != null && playerCount < 1) {
                throw new IllegalArgumentException("question context is invalid");
            }
            currentLessonSection = normalize(currentLessonSection);
            gamePhase = normalize(gamePhase);
            previousQuestion = normalize(previousQuestion);
            if (previousQuestion != null && previousQuestion.length() > 800) {
                throw new IllegalArgumentException("previous question is too long");
            }
            activeExpansions = activeExpansions == null
                    ? Set.of()
                    : activeExpansions.stream()
                            .filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
