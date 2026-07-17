package com.rulepilot.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Game(UUID id, String name, Instant createdAt) {

    public Game {
        Objects.requireNonNull(id, "id is required");
        name = CatalogText.required(name, "game name", 120);
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static Game create(String name, Instant now) {
        return new Game(UUID.randomUUID(), name, now);
    }
}
