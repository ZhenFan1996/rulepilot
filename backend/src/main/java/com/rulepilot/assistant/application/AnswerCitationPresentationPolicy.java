package com.rulepilot.assistant.application;

/** Removes retrieval-only evidence envelopes after validation and before a citation reaches a player. */
final class AnswerCitationPresentationPolicy {

    private static final String VISUAL_RULE_FACTS = "\nVisible rule facts: ";
    private static final String VISIBLE_FACTS = "\nVisible facts: ";
    private static final String EXTRACTED_TEXT =
            "\n\nExtracted page text (may omit inline visual symbols):\n";

    private AnswerCitationPresentationPolicy() {}

    static String excerpt(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.startsWith("Visual-transcribed rule evidence.")) {
            return contentAfter(value, VISUAL_RULE_FACTS);
        }
        if (value.startsWith("Visual page facts (literal observations only;")) {
            return contentAfter(value, VISIBLE_FACTS).replace(EXTRACTED_TEXT, "\n\n").strip();
        }
        return value;
    }

    private static String contentAfter(String value, String marker) {
        int markerStart = value.indexOf(marker);
        return markerStart < 0 ? value : value.substring(markerStart + marker.length()).strip();
    }
}
