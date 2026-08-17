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
        if (setup == null || setup.isBlank()
                || action == null || action.isBlank()
                || outcome == null || outcome.isBlank()
                || basis == null || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule worked example is invalid");
        }
        citationIds = citationIds.stream().distinct().toList();
    }
}
