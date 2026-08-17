package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A cited comparison that says which of two rules applies and whether both still apply outside the conflict. */
public record RulePriorityResolution(
        String baseRule,
        String competingRule,
        String resolution,
        RulePriorityBasis basis,
        List<UUID> citationIds) {

    public RulePriorityResolution {
        if (baseRule == null || baseRule.isBlank()
                || competingRule == null || competingRule.isBlank()
                || resolution == null || resolution.isBlank()
                || basis == null || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("rule priority resolution is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }
}
