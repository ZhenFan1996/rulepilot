package com.rulepilot.recommendation;

/** Lossless, Unicode-aware validation for recommendation conversation turns. */
public final class RecommendationConversationText {

    private RecommendationConversationText() {}

    public static String currentTurn(String value) {
        return playerText(value, true);
    }

    public static String playerTranscriptTurn(String value) {
        return playerText(value, false);
    }

    public static String assistantTranscriptTurn(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return value;
    }

    private static String playerText(String value, boolean allowBlank) {
        String checked = value == null ? "" : value.strip();
        if (!allowBlank && checked.isBlank()) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return checked;
    }

}
