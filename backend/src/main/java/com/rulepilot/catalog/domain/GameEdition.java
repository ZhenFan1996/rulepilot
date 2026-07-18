package com.rulepilot.catalog.domain;

import java.time.Instant;
import java.time.Year;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record GameEdition(
        UUID id,
        UUID gameId,
        String name,
        String language,
        Integer publicationYear,
        Instant createdAt) {

    public GameEdition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(gameId, "gameId is required");
        name = CatalogText.required(name, "edition name", 120);
        language = normalizeLanguage(language);
        if (publicationYear != null && (publicationYear < 1900 || publicationYear > Year.now().getValue() + 2)) {
            throw new IllegalArgumentException("publication year is outside the supported range");
        }
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static GameEdition create(UUID gameId, String name, String language, Integer publicationYear, Instant now) {
        return new GameEdition(UUID.randomUUID(), gameId, name, language, publicationYear, now);
    }

    private static String normalizeLanguage(String language) {
        String normalized = CatalogText.required(language, "language", 20).replace('_', '-');
        if (normalized.equalsIgnoreCase("und")) {
            return "und";
        }
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale.getLanguage().isBlank()) {
            throw new IllegalArgumentException("language must be a valid language tag");
        }
        return locale.toLanguageTag();
    }
}
