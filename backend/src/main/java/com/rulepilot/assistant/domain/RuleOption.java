package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** One cited member of a complete player-facing option set. */
public record RuleOption(
        String decisionContext,
        String selectionRule,
        String optionName,
        String availabilityCondition,
        String result,
        RuleOptionBasis basis,
        List<UUID> citationIds) {

    public RuleOption {
        if (invalid(decisionContext, 240) || invalid(selectionRule, 400) || invalid(optionName, 160)
                || invalid(availabilityCondition, 500) || invalid(result, 700) || basis == null
                || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3) {
            throw new IllegalArgumentException("rule option is invalid");
        }
        decisionContext = decisionContext.strip();
        selectionRule = selectionRule.strip();
        optionName = optionName.strip();
        availabilityCondition = availabilityCondition.strip();
        result = result.strip();
        citationIds = List.copyOf(citationIds);
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }
}
