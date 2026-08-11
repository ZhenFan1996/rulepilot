package com.rulepilot.teaching;

/** Applies syntax-only admission to page-local visual observations. */
public final class VisualRulebookPageClassifier {

    private static final int MIN_FACT_CHARACTERS = 12;

    private VisualRulebookPageClassifier() {}

    /**
     * Semantic page roles belong to the visual model. The application admits a page when the catalog contains a
     * non-trivial factual ledger; it does not reclassify covers, credits, components, or gameplay by keywords.
     */
    public static boolean isSubstantive(int pageNumber, String text) {
        if (pageNumber < 1 || text == null || text.isBlank()) return false;
        String facts = visibleFacts(text);
        long factualCharacters = facts.codePoints().filter(Character::isLetterOrDigit).limit(256).count();
        return factualCharacters >= MIN_FACT_CHARACTERS;
    }

    private static String visibleFacts(String catalogText) {
        String marker = "Visible facts:";
        int facts = catalogText.indexOf(marker);
        if (facts < 0) return catalogText;
        int start = facts + marker.length();
        int keywords = catalogText.indexOf("\nKeywords:", start);
        return keywords < 0 ? catalogText.substring(start) : catalogText.substring(start, keywords);
    }
}
