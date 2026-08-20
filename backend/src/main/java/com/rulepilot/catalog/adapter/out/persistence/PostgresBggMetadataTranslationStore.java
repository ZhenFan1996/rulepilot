package com.rulepilot.catalog.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import com.rulepilot.catalog.application.BggMetadataTranslationStore;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresBggMetadataTranslationStore implements BggMetadataTranslationStore {

    private static final int MAX_PAYLOAD_BYTES = 131_072;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresBggMetadataTranslationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Translation> find(int bggId, String sourceSha256) {
        checkedKey(bggId, sourceSha256);
        return jdbc.query(
                        """
                        SELECT payload::text AS payload
                        FROM bgg_metadata_translation
                        WHERE bgg_id = :bggId AND source_sha256 = :sourceSha256
                        """,
                        new MapSqlParameterSource()
                                .addValue("bggId", bggId)
                                .addValue("sourceSha256", sourceSha256),
                        (result, row) -> read(result.getString("payload")))
                .stream()
                .findFirst();
    }

    @Override
    public void save(int bggId, String sourceSha256, Translation translation, Instant translatedAt) {
        checkedKey(bggId, sourceSha256);
        if (translation == null || translatedAt == null) {
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
                    bgg_id, source_sha256, locale, payload, payload_bytes, translated_at)
                VALUES (:bggId, :sourceSha256, 'zh-CN', CAST(:payload AS jsonb), :payloadBytes, :translatedAt)
                ON CONFLICT (bgg_id, source_sha256) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    translated_at = GREATEST(bgg_metadata_translation.translated_at, EXCLUDED.translated_at)
                """,
                new MapSqlParameterSource()
                        .addValue("bggId", bggId)
                        .addValue("sourceSha256", sourceSha256)
                        .addValue("payload", payload)
                        .addValue("payloadBytes", payloadBytes)
                        .addValue("translatedAt", Timestamp.from(translatedAt)));
    }

    private void checkedKey(int bggId, String sourceSha256) {
        if (bggId <= 0 || sourceSha256 == null || !SHA256.matcher(sourceSha256).matches()) {
            throw new IllegalArgumentException("BGG translation requires a positive id and lowercase SHA-256 digest");
        }
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
