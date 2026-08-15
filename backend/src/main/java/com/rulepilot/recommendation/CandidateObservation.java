package com.rulepilot.recommendation;

import java.text.Normalizer;
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
        id = bounded(id, 80, "candidate observation id");
        if (bggId <= 0) throw new IllegalArgumentException("candidate observation game id must be positive");
        kind = Objects.requireNonNull(kind, "candidate observation kind is required");
        attribute = bounded(attribute, 80, "candidate observation attribute");
        value = bounded(value, 600, "candidate observation value");
        sourceIndexes = sourceIndexes == null ? List.of() : List.copyOf(sourceIndexes);
        if (sourceIndexes.size() > 5
                || sourceIndexes.stream().anyMatch(index -> index == null || index <= 0)
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

    private static String bounded(String value, int maximum, String label) {
        String checked = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
        if (checked.isBlank() || checked.codePointCount(0, checked.length()) > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return checked;
    }

    public enum Kind {
        STRUCTURED_METADATA,
        TAXONOMY,
        ATTRIBUTED_REPORT,
        RULEBOOK_FACT
    }
}
