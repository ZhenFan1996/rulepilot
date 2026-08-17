package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A cited, player-executable tie ruling with no implicit fallback. */
public record RuleTieResolution(
        String tieContext,
        List<String> resolutionSteps,
        String finalOutcome,
        TieResolutionBasis basis,
        List<UUID> citationIds) {

    public RuleTieResolution {
        if (tieContext == null || tieContext.isBlank()
                || resolutionSteps == null || resolutionSteps.isEmpty()
                || resolutionSteps.stream().anyMatch(step -> step == null || step.isBlank())
                || finalOutcome == null || finalOutcome.isBlank()
                || basis == null || citationIds == null || citationIds.isEmpty()) {
            throw new IllegalArgumentException("rule tie resolution is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }
}
