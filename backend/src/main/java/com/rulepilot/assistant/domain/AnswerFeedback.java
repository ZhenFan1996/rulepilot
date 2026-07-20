package com.rulepilot.assistant.domain;

import java.time.Instant;
import java.util.UUID;

public record AnswerFeedback(
        UUID id,
        UUID conversationTurnId,
        UUID gameSessionId,
        Rating rating,
        String createdBy,
        Instant createdAt) {

    public AnswerFeedback {
        if (id == null || conversationTurnId == null || gameSessionId == null || rating == null
                || createdBy == null || createdBy.isBlank() || createdBy.length() > 120 || createdAt == null) {
            throw new IllegalArgumentException("answer feedback is invalid");
        }
        createdBy = createdBy.strip();
    }

    public enum Rating {
        HELPFUL,
        UNCLEAR,
        INCORRECT
    }

    public static AnswerFeedback create(
            UUID conversationTurnId, UUID gameSessionId, Rating rating, String username, Instant now) {
        return new AnswerFeedback(UUID.randomUUID(), conversationTurnId, gameSessionId, rating, username, now);
    }
}
