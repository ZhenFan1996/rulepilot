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
        if (requirement == null || requirement.isBlank() || requirement.length() > 240
                || status == null || playerFact == null || playerFact.length() > 240
                || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule situation check is invalid");
        }
        requirement = requirement.strip();
        playerFact = playerFact.strip();
        citationIds = citationIds.stream().distinct().toList();
        if (citationIds.isEmpty() || citationIds.size() > 3
                || (status == SituationCheckStatus.NOT_PROVIDED) != playerFact.isEmpty()) {
            throw new IllegalArgumentException("rule situation check status and player fact disagree");
        }
    }
}
