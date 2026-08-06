package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BggCatalogImportServiceTest {

    @Test
    void importsGameAndEditionOnceForSameBggId() {
        FakeBgg bgg = new FakeBgg();
        MemoryCatalogRepository repository = new MemoryCatalogRepository();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                repository,
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        var first = service.confirm(266192);
        var repeated = service.confirm(266192);

        assertThat(first.alreadyImported()).isFalse();
        assertThat(repeated.alreadyImported()).isTrue();
        assertThat(repeated.gameId()).isEqualTo(first.gameId());
        assertThat(repeated.editionId()).isEqualTo(first.editionId());
        assertThat(repository.games).hasSize(1);
        assertThat(repository.editions).hasSize(1);
        assertThat(bgg.detailCalls).isEqualTo(1);
    }

    @Test
    void filtersHotDiscoveryByKnownPlayerFitAndKeepsHotOrder() {
        FakeBgg bgg = new FakeBgg();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                new MemoryCatalogRepository(),
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        var games = service.recommendations(4, 90, new BigDecimal("3.0"));

        assertThat(games).extracting(DiscoveryGame::bggId).containsExactly(1002);
    }

    @Test
    void readsSelectionDetailsWithoutCreatingCatalogState() {
        FakeBgg bgg = new FakeBgg();
        MemoryCatalogRepository repository = new MemoryCatalogRepository();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                repository,
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        var details = service.gameDetails(266192);

        assertThat(details.name()).isEqualTo("Wingspan");
        assertThat(bgg.detailCalls).isEqualTo(1);
        assertThat(repository.games).isEmpty();
        assertThat(repository.editions).isEmpty();
    }

    @Test
    void rejectsOutOfRangeRecommendationFiltersBeforeCallingBgg() {
        FakeBgg bgg = new FakeBgg();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                new MemoryCatalogRepository(),
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recommendations(0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("players");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.recommendations(null, 10, new BigDecimal("5.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bgg.discoveryCalls).isZero();
    }

    @Test
    void marksOnlyUnicodeCaseAndSpacingEquivalentExactCandidates() {
        FakeBgg bgg = new FakeBgg();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                new MemoryCatalogRepository(),
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        var candidates = service.findExactCandidates("  ＣＡＳＣＡＤＩＡ   Collector  ");

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).normalizedTitleMatch()).isTrue();
        assertThat(candidates.get(1).normalizedTitleMatch()).isFalse();
        assertThat(bgg.exactQuery).isEqualTo("ＣＡＳＣＡＤＩＡ Collector");
    }

    private static final class FakeBgg implements BoardGameGeekCatalog {
        int detailCalls;
        int discoveryCalls;
        String exactQuery;

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            return List.of(new SearchResult(266192, "Wingspan", 2019));
        }

        @Override
        public List<GameMatch> exactMatches(String query) {
            exactQuery = query;
            return List.of(
                    new GameMatch(100, "cascadia collector", 2025, "", 1, 4, 60, 10),
                    new GameMatch(101, "Cascadia", 2021, "", 1, 4, 45, 10));
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of(new HotGame(1, 266192, "Wingspan", 2019, "https://example.test/wingspan.jpg"));
        }

        @Override
        public List<DiscoveryGame> hotGameDetails() {
            discoveryCalls++;
            return List.of(
                    new DiscoveryGame(
                            1,
                            1001,
                            "Long Heavy Game",
                            2024,
                            "https://example.test/heavy.jpg",
                            2,
                            4,
                            180,
                            new BigDecimal("8.1"),
                            new BigDecimal("4.0"),
                            List.of("Strategy"),
                            List.of("Drafting")),
                    new DiscoveryGame(
                            2,
                            1002,
                            "Fitting Game",
                            2025,
                            "https://example.test/fitting.jpg",
                            2,
                            5,
                            75,
                            new BigDecimal("7.6"),
                            new BigDecimal("2.7"),
                            List.of("Family"),
                            List.of("Set Collection")),
                    new DiscoveryGame(
                            3,
                            1003,
                            "Unknown Fit",
                            2025,
                            "https://example.test/unknown.jpg",
                            null,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            List.of()));
        }

        @Override
        public GameDetails game(int bggId) {
            detailCalls++;
            return new GameDetails(bggId, "Wingspan", "Bird game", "", 2019, 1, 5, 70, 10);
        }
    }

    private static final class MemoryCatalogRepository implements CatalogRepository {
        final List<Game> games = new ArrayList<>();
        final List<GameEdition> editions = new ArrayList<>();
        final List<BggGameMetadata> metadata = new ArrayList<>();

        @Override
        public Game save(Game game) {
            games.add(game);
            return game;
        }

        @Override
        public GameEdition save(GameEdition edition) {
            editions.add(edition);
            return edition;
        }

        @Override
        public Expansion save(Expansion expansion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BggGameMetadata save(BggGameMetadata value) {
            metadata.add(value);
            return value;
        }

        @Override
        public Optional<Game> findGame(UUID gameId) {
            return games.stream().filter(game -> game.id().equals(gameId)).findFirst();
        }

        @Override
        public Optional<Game> findGameByName(String name) {
            return games.stream().filter(game -> game.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public Optional<GameEdition> findEdition(UUID editionId) {
            return editions.stream().filter(edition -> edition.id().equals(editionId)).findFirst();
        }

        @Override
        public Optional<GameEdition> findEdition(UUID gameId, String name, String language) {
            return editions.stream()
                    .filter(edition -> edition.gameId().equals(gameId))
                    .filter(edition -> edition.name().equalsIgnoreCase(name))
                    .filter(edition -> edition.language().equalsIgnoreCase(language))
                    .findFirst();
        }

        @Override
        public Optional<Game> findGameByBggId(int bggId) {
            return metadata.stream()
                    .filter(value -> value.bggId() == bggId)
                    .findFirst()
                    .flatMap(value -> findGame(value.gameId()));
        }

        @Override
        public Optional<BggGameMetadata> findBggMetadata(UUID gameId) {
            return metadata.stream().filter(value -> value.gameId().equals(gameId)).findFirst();
        }

        @Override
        public List<Game> findGames() {
            return List.copyOf(games);
        }

        @Override
        public List<GameEdition> findEditions(UUID gameId) {
            return editions.stream().filter(value -> value.gameId().equals(gameId)).toList();
        }

        @Override
        public List<Expansion> findExpansions(UUID gameId) {
            return List.of();
        }

        @Override
        public Map<UUID, com.rulepilot.catalog.PublicGameCoverLookup.Cover> findCoversByEditions(
                Collection<UUID> editionIds) {
            return Map.of();
        }
    }
}
