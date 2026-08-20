package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBggRankedCatalogTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static PostgresBggRankedCatalog repository;
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
        repository = new PostgresBggRankedCatalog(jdbc);
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

    @Test
    void atomicallyPublishesAndQueriesHotRatingTypeAndTitleOrders() {
        UUID importId = UUID.randomUUID();
        repository.stage(importId, List.of(
                game(10, "Strategy 100%", 10, "8.0", false, Map.of(BggGameType.STRATEGY, 3)),
                game(20, "Family Game", 20, "9.0", false, Map.of(BggGameType.FAMILY, 2)),
                game(30, "Expansion", null, "10.0", true, Map.of(BggGameType.EXPANSION, 1))));
        Snapshot snapshot = new Snapshot(
                Instant.parse("2026-08-07T08:00:00Z"), LocalDate.parse("2026-08-07"), 3, "a".repeat(64));
        repository.publish(importId, snapshot);
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_game_name_alias (bgg_id, alias, locale, source, observed_at)
                VALUES (10, '百变策略', 'zh', 'BGG_OFFICIAL_VERSION', TIMESTAMPTZ '2026-08-07T08:00:00Z')
                """);

        assertThat(repository.findSnapshot()).contains(snapshot);
        assertThat(repository.find(new Query("", BggGameType.ALL, Sort.HOT, 0, 20, List.of(20))).games())
                .extracting(RankedGame::bggId)
                .containsExactly(20, 10);
        assertThat(repository.find(new Query("", BggGameType.ALL, Sort.RATING, 0, 20, List.of())).games())
                .extracting(RankedGame::bggId)
                .containsExactly(20, 10);
        assertThat(repository.find(new Query("", BggGameType.STRATEGY, Sort.RANK, 0, 20, List.of())).games())
                .extracting(RankedGame::bggId)
                .containsExactly(10);
        assertThat(repository.find(new Query("100%", BggGameType.ALL, Sort.RANK, 0, 20, List.of())).games())
                .extracting(RankedGame::sourceName)
                .containsExactly("Strategy 100%");
        assertThat(repository.find(new Query("百变", BggGameType.ALL, Sort.RANK, 0, 20, List.of())).games())
                .extracting(RankedGame::sourceName)
                .containsExactly("Strategy 100%");
        assertThat(repository.findExactName("百变策略"))
                .extracting(RankedGame::bggId)
                .containsExactly(10);
        assertThat(repository.findExactName("strategy 100%"))
                .extracting(RankedGame::bggId)
                .containsExactly(10);
        assertThat(repository.findExactName("百变"))
                .as("partial aliases are useful for browsing but must not establish one exact game identity")
                .isEmpty();
        assertThat(repository.find(new Query("", BggGameType.EXPANSION, Sort.RATING, 0, 20, List.of())).games())
                .extracting(RankedGame::bggId)
                .containsExactly(30);
    }

    private static RankedGame game(
            int id,
            String name,
            Integer rank,
            String rating,
            boolean expansion,
            Map<BggGameType, Integer> types) {
        return new RankedGame(
                id,
                name,
                2026,
                rank,
                new BigDecimal(rating).subtract(new BigDecimal("0.5")),
                new BigDecimal(rating),
                1_000,
                expansion,
                types);
    }
}
