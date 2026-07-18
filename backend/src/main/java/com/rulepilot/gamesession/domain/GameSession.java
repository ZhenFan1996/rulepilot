package com.rulepilot.gamesession.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record GameSession(
        UUID id,
        UUID gameId,
        UUID editionId,
        UUID documentVersionId,
        Set<UUID> expansionIds,
        int playerCount,
        int roundNumber,
        String phase,
        Integer activePlayer,
        String createdBy,
        GameSessionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public GameSession {
        if (id == null || gameId == null || editionId == null || documentVersionId == null
                || expansionIds == null || status == null || createdAt == null || updatedAt == null
                || playerCount < 1 || playerCount > 20 || roundNumber < 1
                || activePlayer != null && (activePlayer < 1 || activePlayer > playerCount)) {
            throw new IllegalArgumentException("game session context is invalid");
        }
        expansionIds = Set.copyOf(expansionIds);
        phase = normalized(phase, "phase", 80);
        createdBy = normalized(createdBy, "creator", 120);
    }

    public static GameSession start(
            UUID gameId,
            UUID editionId,
            UUID documentVersionId,
            Set<UUID> expansionIds,
            int playerCount,
            String phase,
            Integer activePlayer,
            String createdBy,
            Instant now) {
        return new GameSession(
                UUID.randomUUID(), gameId, editionId, documentVersionId, expansionIds, playerCount,
                1, phase, activePlayer, createdBy, GameSessionStatus.ACTIVE, now, now);
    }

    public GameSession updateTurn(int nextRound, String nextPhase, Integer nextActivePlayer, Instant now) {
        if (status != GameSessionStatus.ACTIVE) {
            throw new IllegalStateException("ended game session cannot be updated");
        }
        return new GameSession(
                id, gameId, editionId, documentVersionId, expansionIds, playerCount,
                nextRound, nextPhase, nextActivePlayer, createdBy, status, createdAt, now);
    }

    private static String normalized(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
