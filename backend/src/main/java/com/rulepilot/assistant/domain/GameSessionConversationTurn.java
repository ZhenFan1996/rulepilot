package com.rulepilot.assistant.domain;

import java.time.Instant;
import java.util.UUID;

public record GameSessionConversationTurn(
        UUID id,
        UUID sessionId,
        String question,
        StructuredRuleAnswer answer,
        String createdBy,
        Instant createdAt) {

    public GameSessionConversationTurn {
        if (id == null || sessionId == null || answer == null || createdAt == null
                || question == null || question.isBlank()
                || createdBy == null || createdBy.isBlank() || createdBy.length() > 120) {
            throw new IllegalArgumentException("game session conversation turn is invalid");
        }
        createdBy = createdBy.strip();
    }

    public static GameSessionConversationTurn create(
            UUID sessionId, String question, StructuredRuleAnswer answer, String createdBy, Instant now) {
        return new GameSessionConversationTurn(UUID.randomUUID(), sessionId, question, answer, createdBy, now);
    }
}
