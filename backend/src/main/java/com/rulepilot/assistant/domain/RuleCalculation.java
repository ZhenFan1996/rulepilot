package com.rulepilot.assistant.domain;

/** A deterministic arithmetic derivation kept separate from cited rule claims. */
public record RuleCalculation(String expression, String result) {

    public RuleCalculation {
        if (expression == null || expression.isBlank() || expression.length() > 160
                || result == null || result.isBlank() || result.length() > 80) {
            throw new IllegalArgumentException("rule calculation is invalid");
        }
        expression = expression.strip();
        result = result.strip();
    }
}
