package com.rulepilot.teaching;

import java.util.List;

/**
 * Page-bound transcription of a visible quantitative relation.
 *
 * <p>The structured fields retain scope that a prose summary can accidentally flatten. They are accepted only when
 * their arithmetic is mechanically self-consistent; uncertain observations keep the literal source span and direct
 * the reader back to the cited page without publishing a guessed total.</p>
 */
public record VisualQuantityObservation(
        int pageNumber,
        String ruleGroupIdentifier,
        QuantifierScope quantifierScope,
        String variantAxis,
        Integer variantCount,
        Integer perVariantQuantity,
        Integer derivedTotal,
        String originalSpan,
        QuantityResolution resolution) {

    public VisualQuantityObservation {
        if (pageNumber < 1
                || ruleGroupIdentifier == null || ruleGroupIdentifier.isBlank()
                || quantifierScope == null
                || variantAxis == null
                || originalSpan == null || originalSpan.isBlank()
                || originalSpan.codePoints().anyMatch(Character::isISOControl)
                || resolution == null) {
            throw new IllegalArgumentException("visual quantity observation is invalid");
        }
        requirePositiveQuantity(variantCount, "variant count");
        requirePositiveQuantity(perVariantQuantity, "per-variant quantity");
        requirePositiveQuantity(derivedTotal, "derived total");
        ruleGroupIdentifier = ruleGroupIdentifier.strip();
        variantAxis = variantAxis.strip();
        originalSpan = originalSpan.strip();

        if (resolution == QuantityResolution.TRANSCRIBED_SOURCE_SPAN) {
            if (quantifierScope != QuantifierScope.LITERAL_SOURCE_SPAN
                    || !variantAxis.isBlank()
                    || variantCount != null
                    || perVariantQuantity != null
                    || derivedTotal != null) {
                throw new IllegalArgumentException(
                        "a transcribed quantity source span cannot publish interpreted quantity fields");
            }
        } else if (resolution == QuantityResolution.REQUIRES_PAGE_INSPECTION) {
            if (quantifierScope != QuantifierScope.UNRESOLVED) {
                throw new IllegalArgumentException(
                        "quantity requiring page inspection must keep an unresolved scope");
            }
            if (derivedTotal != null) {
                throw new IllegalArgumentException(
                        "quantity requiring page inspection cannot publish a derived total");
            }
            if (variantCount != null || perVariantQuantity != null) {
                throw new IllegalArgumentException(
                        "quantity requiring page inspection cannot publish uncertain numeric operands");
            }
        } else if (quantifierScope == QuantifierScope.UNRESOLVED) {
            throw new IllegalArgumentException("an exact quantity observation cannot have unresolved scope");
        } else if (quantifierScope == QuantifierScope.PER_VARIANT) {
            if (variantAxis.isBlank() || perVariantQuantity == null) {
                throw new IllegalArgumentException(
                        "an exact per-variant quantity requires an axis and per-variant quantity");
            }
            if (variantCount == null && derivedTotal != null) {
                throw new IllegalArgumentException(
                        "a per-variant derived total requires the visible variant count");
            }
            if (variantCount != null) {
                int exactTotal;
                try {
                    exactTotal = Math.multiplyExact(variantCount, perVariantQuantity);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("visual quantity derived total is outside the safe range", overflow);
                }
                if (derivedTotal == null || derivedTotal != exactTotal) {
                    throw new IllegalArgumentException(
                            "visual quantity derived total does not equal variant count times per-variant quantity");
                }
            }
        } else if (quantifierScope == QuantifierScope.TOTAL) {
            if (variantCount != null || perVariantQuantity != null || derivedTotal == null) {
                throw new IllegalArgumentException(
                        "an exact total quantity must publish only its directly supported total");
            }
        }
    }

    public String evidenceText() {
        StringBuilder evidence = new StringBuilder("Visual quantity observation")
                .append(" | page=").append(pageNumber)
                .append(" | ruleGroup=").append(ruleGroupIdentifier)
                .append(" | scope=").append(quantifierScope);
        if (!variantAxis.isBlank()) evidence.append(" | variantAxis=").append(variantAxis);
        if (variantCount != null) evidence.append(" | variantCount=").append(variantCount);
        if (perVariantQuantity != null) evidence.append(" | perVariantQuantity=").append(perVariantQuantity);
        if (derivedTotal != null) evidence.append(" | derivedTotal=").append(derivedTotal);
        evidence.append(" | resolution=").append(resolution)
                .append(" | originalSpan=").append(originalSpan);
        if (resolution == QuantityResolution.REQUIRES_PAGE_INSPECTION) {
            evidence.append(" | inspect the cited page; no total was inferred");
        } else if (resolution == QuantityResolution.TRANSCRIBED_SOURCE_SPAN) {
            evidence.append(" | literal source span retained; no quantity semantics or arithmetic were inferred");
        }
        return evidence.toString();
    }

    public static String appendEvidence(String factualSummary, List<VisualQuantityObservation> observations) {
        if (factualSummary == null || factualSummary.isBlank()) {
            throw new IllegalArgumentException("visual quantity observations require a factual summary");
        }
        if (observations == null || observations.isEmpty()) return factualSummary.strip();
        String quantityEvidence = observations.stream()
                .map(VisualQuantityObservation::evidenceText)
                .collect(java.util.stream.Collectors.joining("\n"));
        return factualSummary.strip() + "\n" + quantityEvidence;
    }

    private static void requirePositiveQuantity(Integer value, String field) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException("visual quantity " + field + " is invalid");
        }
    }

    public enum QuantifierScope {
        PER_VARIANT,
        TOTAL,
        UNRESOLVED,
        LITERAL_SOURCE_SPAN
    }

    public enum QuantityResolution {
        EXACT,
        REQUIRES_PAGE_INSPECTION,
        TRANSCRIBED_SOURCE_SPAN
    }
}
