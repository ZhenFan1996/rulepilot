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
        if (invalid(leftConcept, 120) || invalid(leftDefinition, 600)
                || invalid(rightConcept, 120) || invalid(rightDefinition, 600)
                || invalid(commonGround, 500) || invalid(keyDifference, 700)
                || invalid(practicalBoundary, 600) || basis == null
                || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3) {
            throw new IllegalArgumentException("rule concept comparison is invalid");
        }
        leftConcept = leftConcept.strip();
        leftDefinition = leftDefinition.strip();
        rightConcept = rightConcept.strip();
        rightDefinition = rightDefinition.strip();
        commonGround = commonGround.strip();
        keyDifference = keyDifference.strip();
        practicalBoundary = practicalBoundary.strip();
        citationIds = List.copyOf(citationIds);
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }
}
