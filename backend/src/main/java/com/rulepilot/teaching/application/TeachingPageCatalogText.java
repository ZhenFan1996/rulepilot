package com.rulepilot.teaching.application;

/** Preserves evidence from across a long page instead of assuming important clauses appear near the top. */
final class TeachingPageCatalogText {

    static final int MAX_CHARACTERS = 3_200;
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
        int middleStart = (value.length() - middleLength) / 2;
        int tailStart = value.length() - tailLength;
        return value.substring(0, headLength)
                + FIRST_GAP
                + value.substring(middleStart, middleStart + middleLength)
                + SECOND_GAP
                + value.substring(tailStart);
    }
}
