package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record RuleWorkedExample(
        String setup,
        String action,
        String outcome,
        WorkedExampleBasis basis,
        List<UUID> citationIds) {

    public RuleWorkedExample {
        if (setup == null || setup.isBlank() || setup.length() > 500
                || action == null || action.isBlank() || action.length() > 700
                || outcome == null || outcome.isBlank() || outcome.length() > 500
                || basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule worked example is invalid");
        }
        setup = setup.strip();
        action = action.strip();
        outcome = outcome.strip();
        citationIds = citationIds.stream().distinct().toList();
    }
}
