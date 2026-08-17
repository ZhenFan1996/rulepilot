package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A published, evidence-backed requirement checked only against facts explicitly stated by the player. */
public record RuleSituationCheck(
        String requirement,
        SituationCheckStatus status,
        String playerFact,
        List<UUID> citationIds) {

    public RuleSituationCheck {
        if (requirement == null || requirement.isBlank()
                || status == null || playerFact == null
                || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule situation check is invalid");
        }
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty() || (status == SituationCheckStatus.NOT_PROVIDED) != playerFact.isBlank()) {
            throw new IllegalArgumentException("rule situation check status and player fact disagree");
        }
    }
}
