package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.sql.DriverManager;
import java.sql.SQLException;
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
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_metadata_translation");
    }

    @Test
    void persistsEachExactSourceTranslationAcrossRepositoryInstances() {
        Translation first = new Translation("第一版简介", List.of("策略"), List.of("轮抽"));
        Translation second = new Translation("第二版简介", List.of("家庭"), List.of("合作"));
        Instant translatedAt = Instant.parse("2026-08-20T08:00:00Z");
        store.save(266192, FIRST_DIGEST, first, translatedAt);
        store.save(266192, SECOND_DIGEST, second, translatedAt.plusSeconds(1));

        PostgresBggMetadataTranslationStore restarted = new PostgresBggMetadataTranslationStore(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThat(restarted.find(266192, FIRST_DIGEST)).contains(first);
        assertThat(restarted.find(266192, SECOND_DIGEST)).contains(second);
        assertThat(restarted.find(266192, "c".repeat(64))).isEmpty();
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
