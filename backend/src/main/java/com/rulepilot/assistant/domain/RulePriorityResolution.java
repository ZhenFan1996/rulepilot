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
        if (baseRule == null || baseRule.isBlank() || baseRule.length() > 500
                || competingRule == null || competingRule.isBlank() || competingRule.length() > 500
                || resolution == null || resolution.isBlank() || resolution.length() > 600
                || basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("rule priority resolution is invalid");
        }
        baseRule = baseRule.strip();
        competingRule = competingRule.strip();
        resolution = resolution.strip();
        citationIds = List.copyOf(citationIds);
    }
}
