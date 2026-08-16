package com.rulepilot.recommendation;

import java.util.Objects;

/** A player-confirmed two-sided constraint that retains the turn which established it. */
public record ConstraintRange<T extends Comparable<? super T>>(
        T minimum,
        T maximum,
        Strength strength,
        String sourceText,
        int confirmedTurn) {

    private static final int MAX_SOURCE_CODE_POINTS = 160;

    public ConstraintRange {
        if (minimum == null && maximum == null) {
            throw new IllegalArgumentException("at least one constraint bound is required");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("constraint minimum must not exceed maximum");
        }
        strength = Objects.requireNonNull(strength, "constraint strength is required");
        sourceText = (sourceText == null ? "" : sourceText).strip();
        if (sourceText.codePointCount(0, sourceText.length()) > MAX_SOURCE_CODE_POINTS) {
            throw new IllegalArgumentException("constraint source text is too long");
        }
        if (confirmedTurn < 0) throw new IllegalArgumentException("confirmed turn must not be negative");
    }

    public static <T extends Comparable<? super T>> ConstraintRange<T> hard(
            T minimum,
            T maximum,
            String sourceText,
            int confirmedTurn) {
        return new ConstraintRange<>(minimum, maximum, Strength.HARD, sourceText, confirmedTurn);
    }

    public static <T extends Comparable<? super T>> ConstraintRange<T> hardExact(T value) {
        return hard(value, value, "", 0);
    }

    public static <T extends Comparable<? super T>> ConstraintRange<T> hardAtMost(T maximum) {
        return hard(null, maximum, "", 0);
    }

    public boolean contains(T value) {
        if (value == null) return false;
        return (minimum == null || minimum.compareTo(value) <= 0)
                && (maximum == null || maximum.compareTo(value) >= 0);
    }

    public boolean exact() {
        return minimum != null && maximum != null && minimum.compareTo(maximum) == 0;
    }

    public boolean hard() {
        return strength == Strength.HARD;
    }

    public enum Strength {
        HARD,
        SOFT
    }
}
