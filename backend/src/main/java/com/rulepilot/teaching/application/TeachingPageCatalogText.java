package com.rulepilot.teaching.application;

/** Preserves evidence from across a long page instead of assuming important clauses appear near the top. */
final class TeachingPageCatalogText {

    static final int MAX_CHARACTERS = 6_400;
    private static final String FIRST_GAP = "\n… [omitted; middle excerpt follows] …\n";
    private static final String SECOND_GAP = "\n… [omitted; final excerpt follows] …\n";

    private TeachingPageCatalogText() {}

    static String bounded(String text) {
        if (text == null) throw new IllegalArgumentException("teaching page text is required");
        String value = text.strip();
        if (value.length() <= MAX_CHARACTERS) return value;

        int contentBudget = MAX_CHARACTERS - FIRST_GAP.length() - SECOND_GAP.length();
        int headLength = contentBudget / 3;
        int middleLength = contentBudget / 3;
        int tailLength = contentBudget - headLength - middleLength;
        int headEnd = endAtTokenBoundary(value, headLength);
        int middleStart = startAtTokenBoundary(value, (value.length() - middleLength) / 2);
        int middleEnd = endAtTokenBoundary(value, middleStart + middleLength);
        int tailStart = startAtTokenBoundary(value, value.length() - tailLength);
        return value.substring(0, headEnd)
                + FIRST_GAP
                + value.substring(middleStart, middleEnd)
                + SECOND_GAP
                + value.substring(tailStart);
    }

    /**
     * The outline Agent copies exact source identifiers from these excerpts. Never expose a synthetic fragment that
     * begins or ends inside an identifier: later canonical-page retrieval quite correctly cannot find that fragment
     * at a token boundary. Moving a cut inward preserves the character budget and keeps the sampled source truthful.
     */
    private static int startAtTokenBoundary(String value, int proposed) {
        int boundary = Math.max(0, Math.min(proposed, value.length()));
        while (boundary < value.length()
                && boundary > 0
                && isIdentifierCharacter(value.charAt(boundary - 1))
                && isIdentifierCharacter(value.charAt(boundary))) {
            boundary++;
        }
        return boundary;
    }

    private static int endAtTokenBoundary(String value, int proposed) {
        int boundary = Math.max(0, Math.min(proposed, value.length()));
        while (boundary > 0
                && boundary < value.length()
                && isIdentifierCharacter(value.charAt(boundary - 1))
                && isIdentifierCharacter(value.charAt(boundary))) {
            boundary--;
        }
        return boundary;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value);
    }
}
