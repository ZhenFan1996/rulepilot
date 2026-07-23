package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.rulepilot.assistant.PlayerLocale;

public interface RuleAnswerCache {

    Optional<StructuredRuleAnswer> find(AnswerCacheKey key);

    void save(AnswerCacheKey key, StructuredRuleAnswer answer);

    record AnswerCacheKey(
            UUID documentVersionId,
            long ruleDataVersion,
            String normalizedQuestion,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<UUID> activeExpansions,
            PlayerLocale outputLanguage) {

        public AnswerCacheKey {
            if (documentVersionId == null || ruleDataVersion < 1
                    || normalizedQuestion == null || normalizedQuestion.isBlank()) {
                throw new IllegalArgumentException("answer cache key is invalid");
            }
            activeExpansions = activeExpansions == null ? Set.of() : Set.copyOf(activeExpansions);
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        }
    }
}
