package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** One player-visible exception or restriction whose condition and effect are independently cited. */
public record RuleExceptionClause(String condition, String effect, List<UUID> citationIds) {

    public RuleExceptionClause {
        if (condition == null || condition.isBlank() || condition.length() > 300
                || effect == null || effect.isBlank() || effect.length() > 500
                || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule exception clause is invalid");
        }
        condition = condition.strip();
        effect = effect.strip();
        citationIds = List.copyOf(citationIds);
    }
}
