package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record RuleWalkthroughStep(
        String instruction,
        String explanation,
        WalkthroughOrderBasis orderBasis,
        List<UUID> citationIds) {

    public RuleWalkthroughStep {
        if (instruction == null || instruction.isBlank()
                || explanation == null || explanation.isBlank()
                || orderBasis == null || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule walkthrough step is invalid");
        }
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty()) throw new IllegalArgumentException("rule walkthrough step requires evidence");
    }
}
