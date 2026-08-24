package com.rulepilot.catalog.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import com.rulepilot.catalog.application.BggMetadataTranslationStore;
import com.rulepilot.catalog.application.BggMetadataTranslationStore.Key;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresBggMetadataTranslationStore implements BggMetadataTranslationStore {

    private static final int MAX_PAYLOAD_BYTES = 131_072;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresBggMetadataTranslationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Translation> find(Key key) {
        if (key == null) throw new IllegalArgumentException("BGG translation key is required");
        return jdbc.query(
                        """
                        SELECT payload::text AS payload
                        FROM bgg_metadata_translation
                        WHERE bgg_id = :bggId
                          AND locale = :locale
                          AND contract_version = :contractVersion
                          AND source_sha256 = :sourceSha256
                        """,
                        new MapSqlParameterSource()
                                .addValue("bggId", key.bggId())
                                .addValue("locale", key.locale())
                                .addValue("contractVersion", key.contractVersion())
                                .addValue("sourceSha256", key.sourceSha256()),
                        (result, row) -> read(result.getString("payload")))
                .stream()
                .findFirst();
    }

    @Override
    public void save(Key key, Translation translation, Instant translatedAt) {
        if (key == null || translation == null || translatedAt == null) {
            throw new IllegalArgumentException("BGG translation and timestamp are required");
        }
        String payload = write(translation);
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("BGG translation exceeds the persistent payload limit");
        }
        jdbc.update(
                """
                INSERT INTO bgg_metadata_translation (
                    bgg_id, locale, contract_version, source_sha256, payload, payload_bytes, translated_at)
                VALUES (:bggId, :locale, :contractVersion, :sourceSha256,
                        CAST(:payload AS jsonb), :payloadBytes, :translatedAt)
                ON CONFLICT (bgg_id, locale, contract_version, source_sha256) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    translated_at = GREATEST(bgg_metadata_translation.translated_at, EXCLUDED.translated_at)
                """,
                new MapSqlParameterSource()
                        .addValue("bggId", key.bggId())
                        .addValue("locale", key.locale())
                        .addValue("contractVersion", key.contractVersion())
                        .addValue("sourceSha256", key.sourceSha256())
                        .addValue("payload", payload)
                        .addValue("payloadBytes", payloadBytes)
                        .addValue("translatedAt", Timestamp.from(translatedAt)));
    }

    private String write(Translation translation) {
        try {
            return json.writeValueAsString(translation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGG translation could not be encoded", exception);
        }
    }

    private Translation read(String payload) {
        try {
            return json.readValue(payload, Translation.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGG translation could not be decoded", exception);
        }
    }
}
