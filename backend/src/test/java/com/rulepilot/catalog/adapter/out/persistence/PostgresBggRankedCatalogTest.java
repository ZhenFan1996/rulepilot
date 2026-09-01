package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogFilters;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataStatus;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataCriterion;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataDimension;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.SelectionEligibility;
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
        repository = new PostgresBggRankedCatalog(jdbc, new ObjectMapper().findAndRegisterModules());
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
                game(10, "Strategy 100%", 2022, 10, "8.0", 5_000, false, Map.of(BggGameType.STRATEGY, 3)),
                game(20, "Family Game", 2026, 20, "9.0", 1_000, false, Map.of(BggGameType.FAMILY, 2)),
                game(30, "Expansion", 2025, null, "10.0", 500, true, Map.of(BggGameType.EXPANSION, 1))));
        Snapshot snapshot = new Snapshot(
                Instant.parse("2026-08-07T08:00:00Z"), LocalDate.parse("2026-08-07"), 3, "a".repeat(64));
        repository.publish(importId, snapshot);
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_game_name_alias (bgg_id, alias, locale, source, observed_at)
                VALUES (10, '百变策略', 'zh', 'BGG_OFFICIAL_VERSION', TIMESTAMPTZ '2026-08-07T08:00:00Z')
                """);
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_metadata_cache
                    (cache_kind, bgg_id, payload, payload_bytes, cached_at, fresh_until, stale_until, last_accessed_at)
                VALUES
                    ('DISCOVERY', 10,
                     '{"name":"Strategy 100%","chineseName":"百变策略","thumbnailUrl":"https://example.test/10-thumb.jpg","imageUrl":"https://example.test/10-full.jpg","categories":["Economic","economic"],"mechanics":["Deck Building"],"designers":["Table Weaver"],"publishers":["Copper Press"],"families":["Industrial Age"],"description":"Build rail networks through industrial cities and smoky canals."}',
                     160, NOW(), NOW() + INTERVAL '1 day', NOW() + INTERVAL '7 days', NOW()),
                    ('DISCOVERY', 20, '{"name":"Family Game","thumbnailUrl":"https://example.test/20-thumb.jpg","categories":["Economic","Family"],"mechanics":["Deck Building"],"designers":["Table Weaver"],"publishers":["Garden Press"],"families":["Peaceful Gardens"],"description":"Welcome animals into a peaceful garden."}', 112, NOW(), NOW() + INTERVAL '1 day', NOW() + INTERVAL '7 days', NOW()),
                    ('DISCOVERY', 30, '{"name":"Expansion","categories":["Economic"],"mechanics":["Deck Building"],"designers":["Table Weaver"],"publishers":["Copper Press"],"families":["Industrial Age"],"description":"More industrial rail networks."}', 112, NOW(), NOW() + INTERVAL '1 day', NOW() + INTERVAL '7 days', NOW())
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
        assertThat(repository.findExactNames(List.of("strategy 100%", "百变策略", "missing")))
                .extracting(match -> match.matchedName() + ":" + match.game().bggId())
                .containsExactly("strategy 100%:10", "百变策略:10");
        assertThat(repository.find(new Query("", BggGameType.EXPANSION, Sort.RATING, 0, 20, List.of())).games())
                .extracting(RankedGame::bggId)
                .containsExactly(30);
        assertThat(repository.searchSelections("百变", 12)).singleElement().satisfies(game -> {
            assertThat(game.bggId()).isEqualTo(10);
            assertThat(game.chineseName()).isEqualTo("百变策略");
            assertThat(game.imageUrl()).isEqualTo("https://example.test/10-full.jpg");
        });
        assertThat(repository.findSelectionsByIds(List.of(20))).singleElement().satisfies(game -> {
            assertThat(game.thumbnailUrl()).isEqualTo("https://example.test/20-thumb.jpg");
            assertThat(game.imageUrl())
                    .as("a thumbnail-only projection must not impersonate the display source")
                    .isEmpty();
        });
        assertThat(repository.searchSelections("变策", 12))
                .as("two-character Chinese fragments should match within an official alias")
                .extracting(game -> game.bggId())
                .containsExactly(10);
        assertThat(repository.searchSelections("pansion", 12))
                .as("the identity grid also has an expansion slot")
                .extracting(game -> game.bggId())
                .containsExactly(30);
        assertThat(repository.findByMetadataFilters(
                        List.of(),
                        List.of("Economic"),
                        List.of("Deck Building"),
                        List.of("table weaver"),
                        20))
                .as("exact BGG taxonomy and designer relations compose and exclude expansions by default")
                .extracting(RankedGame::bggId)
                .containsExactly(10, 20);
        assertThat(repository.findByMetadataFilters(
                        List.of(), List.of(), List.of(), List.of("Table"), 20))
                .as("metadata lookup is an exact relation, not a fuzzy prose search")
                .isEmpty();
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(),
                        List.of("Economic"),
                        List.of("Deck Building"),
                        List.of("Table Weaver"),
                        List.of("Copper Press"),
                        List.of("Industrial Age"),
                        2020,
                        2024,
                        new BigDecimal("7.5"),
                        4_000,
                        null,
                        CatalogSort.POPULARITY,
                        20,
                        0)))
                .as("publisher, family, year, rating and popularity constraints compose locally")
                .extracting(RankedGame::bggId)
                .containsExactly(10);
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, null, CatalogSort.RANK, 1, 1)))
                .as("stable offsets return a new page instead of repeating the first result")
                .extracting(RankedGame::bggId)
                .containsExactly(20);
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, "industrial rail", CatalogSort.RELEVANCE, 20, 0)))
                .as("cached BGG descriptions and tags rank the relevant game first without hard-filtering the slate")
                .extracting(RankedGame::bggId)
                .containsExactly(10, 20);
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, "industrial gardens", CatalogSort.RELEVANCE, 20, 0)))
                .as("concept retrieval recalls both partial and inflected matches without inventing a semantic tie-break")
                .extracting(RankedGame::bggId)
                .containsExactlyInAnyOrder(10, 20);
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, "unmatched constellation", CatalogSort.RELEVANCE, 20, 0)))
                .as("a soft concept query must not erase otherwise eligible ranked games")
                .extracting(RankedGame::bggId)
                .containsExactly(10, 20);

        var canonical = repository.canonicalizeMetadata(List.of(
                new CatalogMetadataCriterion(CatalogMetadataDimension.CATEGORY, "family"),
                new CatalogMetadataCriterion(CatalogMetadataDimension.MECHANIC, "deck building"),
                new CatalogMetadataCriterion(CatalogMetadataDimension.FAMILY, "industrial age"),
                new CatalogMetadataCriterion(CatalogMetadataDimension.DESIGNER, "table weaver"),
                new CatalogMetadataCriterion(CatalogMetadataDimension.PUBLISHER, "copper press")));
        assertThat(canonical.supported()).isTrue();
        assertThat(canonical.complete()).isTrue();
        assertThat(canonical.values())
                .extracting(value -> value.dimension().name() + ":" + value.canonicalValue())
                .containsExactly(
                        "CATEGORY:Family",
                        "MECHANIC:Deck Building",
                        "FAMILY:Industrial Age",
                        "DESIGNER:Table Weaver",
                        "PUBLISHER:Copper Press");
        assertThat(repository.canonicalizeMetadata(List.of(
                                new CatalogMetadataCriterion(CatalogMetadataDimension.MECHANIC, "Deck")))
                        .values())
                .singleElement()
                .satisfies(value -> assertThat(value.status()).isEqualTo(CanonicalMetadataStatus.NOT_FOUND));
        assertThat(repository.canonicalizeMetadata(List.of(
                                new CatalogMetadataCriterion(CatalogMetadataDimension.CATEGORY, "ECONOMIC")))
                        .values())
                .singleElement()
                .satisfies(value -> assertThat(value.status()).isEqualTo(CanonicalMetadataStatus.AMBIGUOUS));
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of("Deck Building"), List.of(), List.of("Missing Publisher"), List.of(),
                        null, null, null, null, null, CatalogSort.RANK, 20, 0)))
                .as("candidate filters may be unsatisfiable without invalidating an independently canonical value")
                .isEmpty();
        assertThat(repository.canonicalizeMetadata(List.of(
                                new CatalogMetadataCriterion(CatalogMetadataDimension.MECHANIC, "Deck Building")))
                        .complete())
                .isTrue();

        UUID incompleteImportId = UUID.randomUUID();
        repository.stage(incompleteImportId, List.of(
                game(40, "Unhydrated Favorite", 2026, 5, "9.5", 9_000, false, Map.of(BggGameType.STRATEGY, 1)),
                game(10, "Strategy 100%", 2022, 10, "8.0", 5_000, false, Map.of(BggGameType.STRATEGY, 3)),
                game(20, "Family Game", 2026, 20, "9.0", 1_000, false, Map.of(BggGameType.FAMILY, 2)),
                game(30, "Expansion", 2025, null, "10.0", 500, true, Map.of(BggGameType.EXPANSION, 1))));
        repository.publish(incompleteImportId, new Snapshot(
                Instant.parse("2026-08-08T08:00:00Z"), LocalDate.parse("2026-08-08"), 4, "b".repeat(64)));
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_metadata_cache
                    (cache_kind, bgg_id, payload, payload_bytes, cached_at, fresh_until, stale_until, last_accessed_at)
                VALUES
                    ('GAME', 40,
                     '{"name":"Unhydrated Favorite","mechanics":["Deck Building"]}',
                     64, NOW(), NOW() + INTERVAL '1 day', NOW() + INTERVAL '7 days', NOW())
                ON CONFLICT (cache_kind, bgg_id) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    cached_at = EXCLUDED.cached_at,
                    fresh_until = EXCLUDED.fresh_until,
                    stale_until = EXCLUDED.stale_until,
                    last_accessed_at = EXCLUDED.last_accessed_at
                """);
        assertThat(repository.findByMetadataFilters(new CatalogFilters(
                        List.of(), List.of(), List.of("Deck Building"), List.of(), List.of(), List.of(),
                        null, null, null, null, null, CatalogSort.RANK, 1, 0)))
                .as("LIMIT applies after excluding rows that the recommendation service cannot hydrate")
                .extracting(RankedGame::bggId)
                .containsExactly(10);
        assertThat(repository.canonicalizeMetadata(List.of(
                                new CatalogMetadataCriterion(CatalogMetadataDimension.MECHANIC, "Deck Building")))
                        .values())
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(CanonicalMetadataStatus.CANONICAL);
                    assertThat(value.canonicalValue()).isEqualTo("Deck Building");
                });
    }

    @Test
    void appliesHardEligibilityAndHydratesTheSameDiscoveryPayloadBeforeLimit() {
        List<RankedGame> ranked = new java.util.ArrayList<>();
        ranked.add(game(999, "Malformed Favorite", 2024, 1, "9.8", 50_000, false,
                Map.of(BggGameType.STRATEGY, 1)));
        for (int index = 0; index < 21; index++) {
            int id = 1_000 + index;
            ranked.add(game(
                    id,
                    "Atomic Candidate " + index,
                    2020 + index % 5,
                    index + 2,
                    "8.0",
                    20_000 - index,
                    false,
                    Map.of(BggGameType.STRATEGY, index + 2)));
        }
        UUID importId = UUID.randomUUID();
        repository.stage(importId, ranked);
        repository.publish(importId, new Snapshot(
                Instant.parse("2026-08-24T08:00:00Z"),
                LocalDate.parse("2026-08-24"),
                ranked.size(),
                "c".repeat(64)));
        putDiscovery(999, "{\"bggId\":999,\"name\":\"Malformed Favorite\",\"mechanics\":[]}");
        for (int index = 0; index < 21; index++) {
            int id = 1_000 + index;
            int maximumMinutes = index < 18 ? 180 : 120;
            putDiscovery(id, discoveryPayload(id, "Atomic Candidate " + index, maximumMinutes));
        }

        var page = repository.findRecommendationCandidates(
                        new CatalogFilters(
                                List.of(BggGameType.STRATEGY),
                                List.of(),
                                List.of("Deck, Bag, and Pool Building"),
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                CatalogSort.RANK,
                                3,
                                0),
                        new SelectionEligibility(3, 3, 90, 120, new BigDecimal("3.0"), null, List.of()))
                .orElseThrow();

        assertThat(page.availableCount()).isEqualTo(3);
        assertThat(page.candidates())
                .extracting(candidate -> candidate.ranking().bggId())
                .containsExactly(1_018, 1_019, 1_020);
        assertThat(page.candidates())
                .allSatisfy(candidate -> {
                    assertThat(candidate.details().bggId()).isEqualTo(candidate.ranking().bggId());
                    assertThat(candidate.details().mechanics())
                            .contains("Deck, Bag, and Pool Building");
                    assertThat(candidate.details().maximumPlayTimeMinutes()).isEqualTo(120);
                });
    }

    private static void putDiscovery(int bggId, String payload) {
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_metadata_cache
                    (cache_kind, bgg_id, payload, payload_bytes, cached_at, fresh_until, stale_until, last_accessed_at)
                VALUES ('DISCOVERY', ?, ?::jsonb, ?, NOW(), NOW() + INTERVAL '1 day',
                        NOW() + INTERVAL '7 days', NOW())
                ON CONFLICT (cache_kind, bgg_id) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    cached_at = EXCLUDED.cached_at,
                    fresh_until = EXCLUDED.fresh_until,
                    stale_until = EXCLUDED.stale_until,
                    last_accessed_at = EXCLUDED.last_accessed_at
                """,
                bggId,
                payload,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private static String discoveryPayload(int bggId, String name, int maximumMinutes) {
        return """
                {"rank":%d,"bggId":%d,"name":"%s","chineseName":"","publicationYear":2024,
                 "thumbnailUrl":"","minPlayers":1,"maxPlayers":5,"playingTimeMinutes":%d,
                 "averageRating":8.0,"averageWeight":3.6,"categories":["Strategy"],
                 "mechanics":["Deck, Bag, and Pool Building"],"minimumPlayTimeMinutes":90,
                 "maximumPlayTimeMinutes":%d,"minimumAge":14,"suggestedMinimumAge":14,
                 "bestWith":"3","recommendedWith":"3","languageDependenceLevel":2,"weightVotes":100,
                 "families":[],"designers":["Independent Designer"],"publishers":["Independent Publisher"],
                 "description":"A complete locally stored discovery record.","imageUrl":""}
                """.formatted(bggId, bggId, name, maximumMinutes, maximumMinutes);
    }

    private static RankedGame game(
            int id,
            String name,
            int year,
            Integer rank,
            String rating,
            int usersRated,
            boolean expansion,
            Map<BggGameType, Integer> types) {
        return new RankedGame(
                id,
                name,
                year,
                rank,
                new BigDecimal(rating).subtract(new BigDecimal("0.5")),
                new BigDecimal(rating),
                usersRated,
                expansion,
                types);
    }
}
