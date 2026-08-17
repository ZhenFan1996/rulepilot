package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** One player-visible rulebook term with its exact meaning, boundary, and direct evidence. */
public record RuleTermDefinition(String term, String definition, String boundary, List<UUID> citationIds) {

    public RuleTermDefinition {
        if (term == null || term.isBlank()
                || definition == null || definition.isBlank()
                || boundary == null
                || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule term definition is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }
}
