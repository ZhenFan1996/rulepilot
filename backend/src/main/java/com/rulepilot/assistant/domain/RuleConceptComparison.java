package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A cited side-by-side distinction between two rulebook concepts that a player may confuse. */
public record RuleConceptComparison(
        String leftConcept,
        String leftDefinition,
        String rightConcept,
        String rightDefinition,
        String commonGround,
        String keyDifference,
        String practicalBoundary,
        ConceptComparisonBasis basis,
        List<UUID> citationIds) {

    public RuleConceptComparison {
        if (invalid(leftConcept) || invalid(leftDefinition)
                || invalid(rightConcept) || invalid(rightDefinition)
                || invalid(commonGround) || invalid(keyDifference)
                || invalid(practicalBoundary) || basis == null
                || citationIds == null || citationIds.isEmpty()) {
            throw new IllegalArgumentException("rule concept comparison is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank();
    }
}
