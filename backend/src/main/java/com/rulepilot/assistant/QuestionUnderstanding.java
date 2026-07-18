package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.Set;
import java.util.UUID;

public interface QuestionUnderstanding {

    UnderstoodQuestion understand(String question, QuestionContext context);

    record QuestionContext(
            UUID documentVersionId,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<UUID> activeExpansions) {

        public QuestionContext {
            if (documentVersionId == null || playerCount != null && playerCount < 1) {
                throw new IllegalArgumentException("question context is invalid");
            }
            currentLessonSection = normalize(currentLessonSection);
            gamePhase = normalize(gamePhase);
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
