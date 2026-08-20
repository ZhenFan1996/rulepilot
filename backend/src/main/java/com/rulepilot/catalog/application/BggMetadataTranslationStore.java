package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.time.Instant;
import java.util.Optional;

/** Durable translations keyed by the exact BGG source payload that produced them. */
public interface BggMetadataTranslationStore {

    Optional<Translation> find(int bggId, String sourceSha256);

    void save(int bggId, String sourceSha256, Translation translation, Instant translatedAt);
}
