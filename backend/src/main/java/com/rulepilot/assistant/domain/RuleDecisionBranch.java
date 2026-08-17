package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record RuleDecisionBranch(
        String condition,
        String outcome,
        DecisionBranchBasis basis,
        List<UUID> citationIds) {

    public RuleDecisionBranch {
        if (condition == null || condition.isBlank()
                || outcome == null || outcome.isBlank()
                || basis == null || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule decision branch is invalid");
        }
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty()) throw new IllegalArgumentException("rule decision branch requires evidence");
    }
}
