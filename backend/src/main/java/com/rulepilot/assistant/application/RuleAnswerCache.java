package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RuleAnswerCache {

    Optional<StructuredRuleAnswer> find(AnswerCacheKey key);

    void save(AnswerCacheKey key, StructuredRuleAnswer answer);

    record AnswerCacheKey(
            UUID documentVersionId,
            String normalizedQuestion,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<String> activeExpansions) {

        public AnswerCacheKey {
            if (documentVersionId == null || normalizedQuestion == null || normalizedQuestion.isBlank()) {
                throw new IllegalArgumentException("answer cache key is invalid");
            }
            activeExpansions = activeExpansions == null ? Set.of() : Set.copyOf(activeExpansions);
        }
    }
}
