package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A cited ruling about whether a conditional rule governs the player's stated game setup. */
public record RuleScopeResolution(
        String ruleContext,
        String governingCondition,
        String currentSituation,
        ScopeMatchStatus matchStatus,
        String effect,
        ScopeBasis basis,
        List<UUID> citationIds) {

    public RuleScopeResolution {
        if (ruleContext == null || ruleContext.isBlank()
                || governingCondition == null || governingCondition.isBlank()
                || currentSituation == null || currentSituation.isBlank()
                || matchStatus == null || effect == null || effect.isBlank()
                || basis == null || citationIds == null || citationIds.isEmpty()) {
            throw new IllegalArgumentException("rule scope resolution is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }
}
