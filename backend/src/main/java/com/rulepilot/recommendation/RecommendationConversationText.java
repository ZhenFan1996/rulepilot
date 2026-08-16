package com.rulepilot.recommendation;

/** Lossless, Unicode-aware validation for recommendation conversation turns. */
public final class RecommendationConversationText {

    public static final int MAX_CODE_POINTS = 500;
    private static final int MAX_ASSISTANT_CODE_POINTS = 1_200;

    private RecommendationConversationText() {}

    public static String currentTurn(String value) {
        return playerText(value, true);
    }

    public static String playerTranscriptTurn(String value) {
        return playerText(value, false);
    }

    public static String assistantTranscriptTurn(String value) {
        return checked(value, false, MAX_ASSISTANT_CODE_POINTS, false);
    }

    private static String playerText(String value, boolean allowBlank) {
        return checked(value, allowBlank, MAX_CODE_POINTS, true);
    }

    private static String checked(String value, boolean allowBlank, int maximum, boolean playerAuthored) {
        String checked = value == null ? "" : value.strip();
        int codePoints = checked.codePointCount(0, checked.length());
        if (codePoints > maximum && playerAuthored) {
            throw new RecommendationConversationInputException(
                    RecommendationConversationInputException.Code.MESSAGE_TOO_LONG,
                    maximum,
                    codePoints);
        }
        if (codePoints > maximum || !allowBlank && checked.isBlank()) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return checked;
    }
}
