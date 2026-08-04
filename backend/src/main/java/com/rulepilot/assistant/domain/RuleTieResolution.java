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
        if (tieContext == null || tieContext.isBlank() || tieContext.length() > 500
                || resolutionSteps == null || resolutionSteps.isEmpty() || resolutionSteps.size() > 6
                || resolutionSteps.stream().anyMatch(step -> step == null || step.isBlank()
                        || step.length() > 500 || step.contains("\n") || step.contains("\r"))
                || finalOutcome == null || finalOutcome.isBlank() || finalOutcome.length() > 500
                || basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3) {
            throw new IllegalArgumentException("rule tie resolution is invalid");
        }
        tieContext = tieContext.strip();
        resolutionSteps = resolutionSteps.stream().map(String::strip).toList();
        finalOutcome = finalOutcome.strip();
        citationIds = List.copyOf(citationIds);
    }
}
