package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record RuleDecisionBranch(
        String condition,
        String outcome,
        DecisionBranchBasis basis,
        List<UUID> citationIds) {

    public RuleDecisionBranch {
        if (condition == null || condition.isBlank() || condition.length() > 300
                || outcome == null || outcome.isBlank() || outcome.length() > 500
                || basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule decision branch is invalid");
        }
        condition = condition.strip();
        outcome = outcome.strip();
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty()) throw new IllegalArgumentException("rule decision branch requires evidence");
    }
}
