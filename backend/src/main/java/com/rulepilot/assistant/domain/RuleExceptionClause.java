package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** One player-visible exception or restriction whose condition and effect are independently cited. */
public record RuleExceptionClause(String condition, String effect, List<UUID> citationIds) {

    public RuleExceptionClause {
        if (condition == null || condition.isBlank()
                || effect == null || effect.isBlank()
                || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule exception clause is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }
}
