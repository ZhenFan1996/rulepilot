package com.rulepilot.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BggGameMetadata(
        UUID gameId,
        int bggId,
        String description,
        String thumbnailUrl,
        Integer minPlayers,
        Integer maxPlayers,
        Integer playingTimeMinutes,
        Integer minimumAge,
        Instant importedAt) {

    public BggGameMetadata {
        Objects.requireNonNull(gameId, "gameId is required");
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        description = description == null ? "" : description;
        thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        Objects.requireNonNull(importedAt, "importedAt is required");
    }
}
