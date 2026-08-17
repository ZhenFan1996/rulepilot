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
        if (invalid(decisionContext) || invalid(selectionRule) || invalid(optionName)
                || invalid(availabilityCondition) || invalid(result) || basis == null
                || citationIds == null || citationIds.isEmpty()) {
            throw new IllegalArgumentException("rule option is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank();
    }
}
