package com.rulepilot.recommendation;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/** A publishable candidate statement whose evidence capability and candidate identity are validated. */
public record CandidateClaim(
        int bggId,
        String subject,
        Type type,
        ConstraintRange.Strength strength,
        Relation relation,
        String text,
        List<CandidateObservation> evidence) {

    public CandidateClaim {
        if (bggId <= 0) throw new IllegalArgumentException("candidate claim game id must be positive");
        String checkedSubject = bounded(subject, 80, "candidate claim subject");
        subject = checkedSubject;
        Type checkedType = Objects.requireNonNull(type, "candidate claim type is required");
        type = checkedType;
        relation = Objects.requireNonNull(relation, "candidate claim relation is required");
        text = bounded(text, 600, "candidate claim text");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (evidence.size() > 5 || evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidate claim evidence is invalid");
        }
        if (relation == Relation.UNKNOWN && !evidence.isEmpty()) {
            throw new IllegalArgumentException("an unknown candidate claim must not pretend related material is evidence");
        }
        if (relation != Relation.UNKNOWN && evidence.isEmpty()) {
            throw new IllegalArgumentException("a definite candidate claim requires evidence");
        }
        if (evidence.stream().anyMatch(observation -> observation.bggId() != bggId)) {
            throw new IllegalArgumentException("candidate claim evidence must belong to the same candidate");
        }
        if (evidence.stream().anyMatch(observation -> !observation.supports(checkedType))) {
            throw new IllegalArgumentException("candidate observation capability cannot support this claim type");
        }
        if (type == Type.CONSTRAINT_FIT && strength == null) {
            throw new IllegalArgumentException("a constraint fit claim requires its hard or soft strength");
        }
        if (type != Type.CONSTRAINT_FIT && strength != null) {
            throw new IllegalArgumentException("only a constraint fit claim may have constraint strength");
        }
        if (type == Type.CONSTRAINT_FIT && relation == Relation.OBSERVED
                || type != Type.CONSTRAINT_FIT
                        && relation != Relation.OBSERVED
                        && relation != Relation.UNKNOWN) {
            throw new IllegalArgumentException("candidate claim type and relation are inconsistent");
        }
        if (!evidence.isEmpty()
                && evidence.stream().noneMatch(observation -> observation.attribute().equals(checkedSubject))) {
            throw new IllegalArgumentException("candidate claim evidence must match the claim subject");
        }
    }

    public List<Integer> sourceIndexes() {
        return evidence.stream()
                .flatMap(observation -> observation.sourceIndexes().stream())
                .distinct()
                .toList();
    }

    public enum Type {
        CONSTRAINT_FIT,
        STRUCTURED_FACT,
        TAXONOMY_CLASSIFICATION,
        ATTRIBUTED_EXPERIENCE,
        RULE_PROCEDURE,
        PREFERENCE_INFERENCE
    }

    public enum Relation {
        SATISFIED,
        CONFLICT,
        UNKNOWN,
        OBSERVED
    }

    private static String bounded(String value, int maximum, String label) {
        String checked = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
        if (checked.isBlank() || checked.codePointCount(0, checked.length()) > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return checked;
    }
}
