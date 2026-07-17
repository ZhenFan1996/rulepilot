package com.rulepilot.catalog.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Expansion(UUID id, UUID gameId, String name, Set<UUID> compatibleEditionIds, Instant createdAt) {

    public Expansion {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(gameId, "gameId is required");
        name = CatalogText.required(name, "expansion name", 120);
        compatibleEditionIds = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(
                compatibleEditionIds, "compatible editions are required")));
        if (compatibleEditionIds.isEmpty()) {
            throw new IllegalArgumentException("an expansion must support at least one edition");
        }
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static Expansion create(UUID gameId, String name, Set<UUID> compatibleEditionIds, Instant now) {
        return new Expansion(UUID.randomUUID(), gameId, name, compatibleEditionIds, now);
    }
}
