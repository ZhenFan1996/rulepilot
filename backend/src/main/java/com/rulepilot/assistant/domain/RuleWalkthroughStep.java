package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record RuleWalkthroughStep(
        String instruction,
        String explanation,
        WalkthroughOrderBasis orderBasis,
        List<UUID> citationIds) {

    public RuleWalkthroughStep {
        if (instruction == null || instruction.isBlank() || instruction.length() > 240
                || explanation == null || explanation.isBlank() || explanation.length() > 500
                || orderBasis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule walkthrough step is invalid");
        }
        instruction = instruction.strip();
        explanation = explanation.strip();
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty()) throw new IllegalArgumentException("rule walkthrough step requires evidence");
    }
}
