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
        if (ruleContext == null || ruleContext.isBlank() || ruleContext.length() > 500
                || governingCondition == null || governingCondition.isBlank() || governingCondition.length() > 500
                || currentSituation == null || currentSituation.isBlank() || currentSituation.length() > 300
                || matchStatus == null || effect == null || effect.isBlank() || effect.length() > 600
                || basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3) {
            throw new IllegalArgumentException("rule scope resolution is invalid");
        }
        ruleContext = ruleContext.strip();
        governingCondition = governingCondition.strip();
        currentSituation = currentSituation.strip();
        effect = effect.strip();
        citationIds = List.copyOf(citationIds);
    }
}
