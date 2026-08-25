package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import com.rulepilot.catalog.application.BggMetadataTranslationStore.Key;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBggMetadataTranslationStoreTest {

    private static final String FIRST_DIGEST = "a".repeat(64);
    private static final String SECOND_DIGEST = "b".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static PostgresBggMetadataTranslationStore store;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        store = new PostgresBggMetadataTranslationStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void clearTranslations() {
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_metadata_translation_versioned");
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_metadata_translation");
    }

    @Test
    void persistsEachExactSourceTranslationAcrossRepositoryInstances() {
        Translation first = new Translation("第一版简介", List.of("策略"), List.of("轮抽"));
        Translation second = new Translation("第二版简介", List.of("家庭"), List.of("合作"));
        Instant translatedAt = Instant.parse("2026-08-20T08:00:00Z");
        Key firstKey = new Key(266192, "zh-CN", 5, FIRST_DIGEST);
        Key secondKey = new Key(266192, "zh-CN", 5, SECOND_DIGEST);
        store.save(firstKey, first, translatedAt);
        store.save(secondKey, second, translatedAt.plusSeconds(1));

        PostgresBggMetadataTranslationStore restarted = new PostgresBggMetadataTranslationStore(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThat(restarted.find(firstKey)).contains(first);
        assertThat(restarted.find(secondKey)).contains(second);
        assertThat(restarted.find(new Key(266192, "zh-CN", 5, "c".repeat(64)))).isEmpty();
    }

    @Test
    void isolatesTheSameSourceByLocaleAndTranslationContract() {
        Translation zhV5 = new Translation("第五版", List.of("策略"), List.of("轮抽"));
        Translation zhV6 = new Translation("第六版", List.of("谋略"), List.of("选牌"));
        Translation enV5 = new Translation("Version five", List.of("Strategy"), List.of("Drafting"));
        Instant translatedAt = Instant.parse("2026-08-20T08:00:00Z");
        Key zhV5Key = new Key(266192, "zh-CN", 5, FIRST_DIGEST);
        Key zhV6Key = new Key(266192, "zh-CN", 6, FIRST_DIGEST);
        Key enV5Key = new Key(266192, "en", 5, FIRST_DIGEST);

        store.save(zhV5Key, zhV5, translatedAt);
        store.save(zhV6Key, zhV6, translatedAt);
        store.save(enV5Key, enV5, translatedAt);

        assertThat(store.find(zhV5Key)).contains(zhV5);
        assertThat(store.find(zhV6Key)).contains(zhV6);
        assertThat(store.find(enV5Key)).contains(enV5);
    }

    @Test
    void keepsPreviousReleaseWritesCompatibleDuringRollingDeployment() throws Exception {
        Instant translatedAt = Instant.parse("2026-08-20T08:00:00Z");
        Key currentKey = new Key(266192, "zh-CN", 5, FIRST_DIGEST);
        Translation current = new Translation("当前契约", List.of("策略"), List.of("轮抽"));
        Translation firstLegacy = new Translation("旧契约初稿", List.of("家庭"), List.of("合作"));
        Translation updatedLegacy = new Translation("旧契约更新", List.of("家庭"), List.of("合作"));

        store.save(currentKey, current, translatedAt);
        saveWithPreviousReleaseSql(firstLegacy, translatedAt.plusSeconds(1));
        saveWithPreviousReleaseSql(updatedLegacy, translatedAt.plusSeconds(2));

        assertThat(store.find(currentKey)).contains(current);
        assertThat(store.find(new Key(266192, "zh-CN", 4, FIRST_DIGEST)))
                .contains(updatedLegacy);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                        "SELECT count(*) FROM bgg_metadata_translation WHERE bgg_id = 266192", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.getJdbcTemplate().queryForList(
                        """
                        SELECT key_usage.column_name
                        FROM information_schema.table_constraints constraints
                        JOIN information_schema.key_column_usage key_usage
                          ON key_usage.constraint_schema = constraints.constraint_schema
                         AND key_usage.constraint_name = constraints.constraint_name
                        WHERE constraints.table_schema = 'public'
                          AND constraints.table_name = 'bgg_metadata_translation'
                          AND constraints.constraint_type = 'PRIMARY KEY'
                        ORDER BY key_usage.ordinal_position
                        """,
                        String.class))
                .containsExactly("bgg_id", "source_sha256");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                        """
                        SELECT count(*)
                        FROM bgg_metadata_translation_versioned
                        WHERE bgg_id = 266192 AND source_sha256 = ?
                        """,
                        Integer.class,
                        FIRST_DIGEST))
                .isEqualTo(2);
    }

    private void saveWithPreviousReleaseSql(Translation translation, Instant translatedAt) throws Exception {
        String payload = new ObjectMapper().findAndRegisterModules().writeValueAsString(translation);
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_metadata_translation (
                    bgg_id, source_sha256, locale, payload, payload_bytes, translated_at)
                VALUES (?, ?, 'zh-CN', CAST(? AS jsonb), ?, ?)
                ON CONFLICT (bgg_id, source_sha256) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    translated_at = GREATEST(
                        bgg_metadata_translation.translated_at,
                        EXCLUDED.translated_at)
                """,
                266192,
                FIRST_DIGEST,
                payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                Timestamp.from(translatedAt));
    }

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the production PostgreSQL extensions", exception);
        }
    }
}
