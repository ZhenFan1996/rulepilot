package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** One player-visible rulebook term with its exact meaning, boundary, and direct evidence. */
public record RuleTermDefinition(String term, String definition, String boundary, List<UUID> citationIds) {

    public RuleTermDefinition {
        if (term == null || term.isBlank() || term.length() > 120
                || definition == null || definition.isBlank() || definition.length() > 600
                || boundary == null || boundary.length() > 400
                || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule term definition is invalid");
        }
        term = term.strip();
        definition = definition.strip();
        boundary = boundary.strip();
        citationIds = List.copyOf(citationIds);
    }
}
