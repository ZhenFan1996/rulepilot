package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.application.BggMetadataCache.CacheWindow;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBggMetadataCacheTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static PostgresBggMetadataCache repository;
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
        repository = new PostgresBggMetadataCache(jdbc, new ObjectMapper().findAndRegisterModules(), 262_144);
    }

    @BeforeEach
    void clearCache() {
        JdbcTemplate template = new JdbcTemplate(jdbc.getJdbcTemplate().getDataSource());
        template.update("DELETE FROM bgg_metadata_cache");
        template.update("DELETE FROM bgg_game_name_alias");
    }

    @Test
    void scansAllCurrentSourceIdentitiesAndPrefersTheDetailPageSource() {
        Instant now = Instant.parse("2026-09-05T08:00:00Z");
        repository.putDiscoveryGames(List.of(discovery(41, "Discovery"), discovery(90001, "Outside ranking")), window(now));
        repository.putGame(details(41, "Detail source"), window(now));
        repository.putDiscoveryGames(List.of(discovery(99, "Expired")),
                new CacheWindow(now.minusSeconds(30), now.minusSeconds(20), now.minusSeconds(10)));

        var first = repository.translationSources(0, 1, now);
        assertThat(first).singleElement().satisfies(source -> {
            assertThat(source.bggId()).isEqualTo(41);
            assertThat(source.gameName()).isEqualTo("Detail source");
        });
        assertThat(repository.translationSources(41, 10, now)).singleElement()
                .satisfies(source -> assertThat(source.bggId()).isEqualTo(90001));
        assertThat(repository.translationSources(90001, 10, now)).isEmpty();
    }

    @Test
    void persistsTypedMetadataAcrossRepositoryInstances() {
        Instant cachedAt = Instant.parse("2026-08-07T08:00:00Z");
        CacheWindow window = window(cachedAt);
        List<HotGame> hot = List.of(new HotGame(1, 266192, "Wingspan", 2019, "thumb"));
        DiscoveryGame discovery = discovery(266192, "Wingspan");
        GameDetails details = details(266192, "Wingspan");
        repository.putHotGames(hot, window);
        repository.putDiscoveryGames(List.of(discovery), window);
        repository.putGame(details, window);

        PostgresBggMetadataCache restarted =
                new PostgresBggMetadataCache(jdbc, new ObjectMapper().findAndRegisterModules(), 262_144);
        Instant accessedAt = cachedAt.plusSeconds(60);

        assertThat(restarted.hotGames(accessedAt).orElseThrow().value()).isEqualTo(hot);
        assertThat(restarted.discoveryGames(List.of(266192), accessedAt).get(266192).value())
                .isEqualTo(discovery);
        assertThat(restarted.game(266192, accessedAt).orElseThrow().value()).isEqualTo(details);
    }

    @Test
    void servesStaleEntriesOnlyInsideTheirStaleWindow() {
        Instant cachedAt = Instant.parse("2026-08-07T08:00:00Z");
        repository.putGame(details(1, "Stale game"), window(cachedAt));

        var stale = repository.game(1, cachedAt.plusSeconds(7_200)).orElseThrow();

        assertThat(stale.freshAt(cachedAt.plusSeconds(7_200))).isFalse();
        assertThat(repository.game(1, cachedAt.plusSeconds(86_400))).isEmpty();
    }

    @Test
    void retainsOfficialChineseAliasesAfterTheMetadataCacheExpires() {
        Instant cachedAt = Instant.parse("2026-08-07T08:00:00Z");
        repository.putDiscoveryGames(List.of(discovery(266192, "Wingspan")), window(cachedAt));

        repository.prune(cachedAt.plusSeconds(86_400), 10, 1_000_000);

        assertThat(jdbc.getJdbcTemplate().queryForList(
                        "SELECT alias FROM bgg_game_name_alias WHERE bgg_id = 266192", String.class))
                .containsExactly("展翅翱翔");
    }

    @Test
    void prunesExpiredAndLeastRecentlyUsedEntriesToCapacity() {
        Instant start = Instant.parse("2026-08-07T08:00:00Z");
        repository.putGame(details(1, "Recently viewed"), window(start));
        repository.putGame(details(2, "Expired"), new CacheWindow(start, start, start.plusSeconds(5)));
        repository.putGame(details(3, "Newest"), window(start.plusSeconds(2)));
        repository.game(1, start.plusSeconds(960));

        var result = repository.prune(start.plusSeconds(961), 1, 1_000_000);

        assertThat(result.expiredEntries()).isEqualTo(1);
        assertThat(result.capacityEntries()).isEqualTo(1);
        assertThat(repository.game(1, start.plusSeconds(962))).isPresent();
        assertThat(repository.game(2, start.plusSeconds(962))).isEmpty();
        assertThat(repository.game(3, start.plusSeconds(962))).isEmpty();
    }

    private static CacheWindow window(Instant cachedAt) {
        return new CacheWindow(cachedAt, cachedAt.plusSeconds(3_600), cachedAt.plusSeconds(86_400));
    }

    private static DiscoveryGame discovery(int id, String name) {
        return new DiscoveryGame(
                1,
                id,
                name,
                "展翅翱翔",
                2019,
                "thumb",
                1,
                5,
                70,
                new BigDecimal("8.1"),
                new BigDecimal("2.4"),
                List.of("Strategy"),
                List.of("Card Drafting"));
    }

    private static GameDetails details(int id, String name) {
        return new GameDetails(
                id,
                name,
                "Description",
                "thumb",
                2019,
                1,
                5,
                70,
                10,
                "image",
                new BigDecimal("8.1"),
                new BigDecimal("2.4"),
                List.of("Strategy"),
                List.of("Card Drafting"),
                List.of("Designer"),
                List.of("Publisher"),
                List.of("展翅翱翔"));
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
