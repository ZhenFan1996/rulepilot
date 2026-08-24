package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.time.Instant;
import java.util.Optional;

/** Durable translations keyed by the exact BGG source payload that produced them. */
public interface BggMetadataTranslationStore {

    Optional<Translation> find(Key key);

    void save(Key key, Translation translation, Instant translatedAt);

    record Key(int bggId, String locale, int contractVersion, String sourceSha256) {
        public Key {
            if (bggId <= 0) throw new IllegalArgumentException("BGG translation id must be positive");
            locale = locale == null ? "" : locale.strip();
            if (locale.isBlank() || locale.length() > 16) {
                throw new IllegalArgumentException("BGG translation locale is invalid");
            }
            if (contractVersion <= 0) {
                throw new IllegalArgumentException("BGG translation contract version must be positive");
            }
            if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("BGG translation source digest is invalid");
            }
        }
    }
}
