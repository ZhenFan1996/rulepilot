package com.rulepilot.recommendation.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/** Exact equivalence for BGG taxonomy labels that were renamed by BGG. */
final class BoardGameRecommendationTaxonomy {

    private static final Map<String, String> RENAMED_LABELS = Map.of(
            "area control", "area majority influence");

    private BoardGameRecommendationTaxonomy() {}

    static boolean equivalent(String requested, String catalogValue) {
        String left = canonical(requested);
        String right = canonical(catalogValue);
        return !left.isBlank() && left.equals(right);
    }

    private static String canonical(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
        return RENAMED_LABELS.getOrDefault(normalized, normalized);
    }
}
