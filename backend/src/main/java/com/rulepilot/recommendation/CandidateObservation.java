package com.rulepilot.recommendation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Candidate-scoped evidence whose kind determines which claim classes it may support. */
public record CandidateObservation(
        String id,
        int bggId,
        Kind kind,
        String attribute,
        String value,
        List<Integer> sourceIndexes) {

    public CandidateObservation {
        id = requiredToken(id, "candidate observation id");
        if (bggId <= 0) throw new IllegalArgumentException("candidate observation game id must be positive");
        kind = Objects.requireNonNull(kind, "candidate observation kind is required");
        attribute = requiredToken(attribute, "candidate observation attribute");
        value = requiredText(value, "candidate observation value");
        sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
        if (sourceIndexes.stream().anyMatch(index -> index == null || index <= 0)
                || new LinkedHashSet<>(sourceIndexes).size() != sourceIndexes.size()) {
            throw new IllegalArgumentException("candidate observation source indexes are invalid");
        }
        if (kind == Kind.ATTRIBUTED_REPORT && sourceIndexes.isEmpty()) {
            throw new IllegalArgumentException("an attributed candidate observation requires a source");
        }
    }

    public boolean supports(CandidateClaim.Type claimType) {
        return switch (kind) {
            case STRUCTURED_METADATA -> claimType == CandidateClaim.Type.CONSTRAINT_FIT
                    || claimType == CandidateClaim.Type.STRUCTURED_FACT;
            case TAXONOMY -> claimType == CandidateClaim.Type.CONSTRAINT_FIT
                    || claimType == CandidateClaim.Type.TAXONOMY_CLASSIFICATION
                    || claimType == CandidateClaim.Type.PREFERENCE_INFERENCE;
            case ATTRIBUTED_REPORT -> claimType == CandidateClaim.Type.ATTRIBUTED_EXPERIENCE;
            case RULEBOOK_FACT -> claimType == CandidateClaim.Type.RULE_PROCEDURE
                    || claimType == CandidateClaim.Type.STRUCTURED_FACT;
        };
    }

    private static String requiredToken(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is invalid");
        return value.strip();
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is invalid");
        return value;
    }

    public enum Kind {
        STRUCTURED_METADATA,
        TAXONOMY,
        ATTRIBUTED_REPORT,
        RULEBOOK_FACT
    }
}
