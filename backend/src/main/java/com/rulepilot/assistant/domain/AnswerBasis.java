package com.rulepilot.assistant.domain;

/** States whether a player-facing ruling is quoted directly or applies cited rules to stated table facts. */
public enum AnswerBasis {
    DIRECT_RULE,
    GROUNDED_APPLICATION;

    public static AnswerBasis fromModelValue(String value) {
        if (value == null || value.isBlank()) {
            return DIRECT_RULE;
        }
        return AnswerBasis.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
