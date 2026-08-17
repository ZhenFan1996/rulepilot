package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BggRankedCatalogServiceTest {

    @Test
    void keepsTheFullSnapshotPageWhileHydratingOnlyItsTwentyCandidates() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.browse("", BggGameType.STRATEGY, Sort.HOT, 0, 20);

        assertThat(result.snapshot()).isPresent();
        assertThat(result.total()).isEqualTo(162_686);
        assertThat(repository.query.type()).isEqualTo(BggGameType.STRATEGY);
        assertThat(repository.query.hotIds()).containsExactly(20, 10);
        assertThat(bgg.detailIds).containsExactly(10, 20);
        assertThat(result.games().getFirst().hotRank()).isEqualTo(2);
        assertThat(result.games().getFirst().details().chineseName()).isEqualTo("策略十号");
    }

    @Test
    void returnsTheRankedSnapshotWithoutCallingBggDetailsForTheFastFirstPaint() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.browse("", BggGameType.STRATEGY, Sort.RANK, 0, 20, false);

        assertThat(result.games()).allSatisfy(game -> assertThat(game.details()).isNull());
        assertThat(bgg.detailIds).isEmpty();
    }

    @Test
    void loadsTheRichBggRecordForAFocusedRecommendationConversation() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.findGameById(10);

        assertThat(result).hasValueSatisfying(game -> {
            assertThat(game.details().officialChineseName()).isEqualTo("策略十号");
            assertThat(game.details().description()).contains("deploy agents");
            assertThat(game.details().mechanics()).contains("Worker Placement", "Deck Building");
            assertThat(game.details().imageUrl()).isEqualTo("https://example.test/10.jpg");
        });
    }

    @Test
    void keepsOneBestTitleMatchPerAgentSuggestionInsteadOfFillingThePoolWithVariants() {
        MemoryRepository repository = new MemoryRepository();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, new FakeBgg());

        var result = service.searchByNames(List.of("Game 20", "Game 10"));

        assertThat(result).extracting(game -> game.bggId()).containsExactly(20, 10);
    }

    @Test
    void resolvesAnExactLocalizedAliasThroughBggAndHydratesItsCanonicalDetails() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.resolveReferenceTitle("白塔庭院");

        assertThat(result).singleElement().satisfies(game -> {
            assertThat(game.ranking().bggId()).isEqualTo(20);
            assertThat(game.ranking().sourceName()).isEqualTo("Game 20");
            assertThat(game.details().mechanics()).contains("Worker Placement", "Deck Building");
        });
        assertThat(bgg.searchQueries).containsExactly("白塔庭院");
    }

    @Test
    void resolvesOneExplicitBilingualTitlePairThroughItsLocallyRankedCanonicalAlias() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.resolveReferenceTitle("白塔庭院（Game 20）");

        assertThat(result).singleElement().satisfies(game -> {
            assertThat(game.ranking().bggId()).isEqualTo(20);
            assertThat(game.ranking().sourceName()).isEqualTo("Game 20");
        });
        assertThat(bgg.searchQueries)
                .as("an explicit canonical alias already present in the ranked catalog needs no remote title search")
                .isEmpty();
    }

    @Test
    void resolvesAnExactSingleCharacterReferenceAlias() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.resolveReferenceTitle("碁");

        assertThat(result).singleElement().satisfies(game -> {
            assertThat(game.ranking().bggId()).isEqualTo(30);
            assertThat(game.ranking().sourceName()).isEqualTo("碁");
        });
        assertThat(bgg.searchQueries).isEmpty();
    }

    @Test
    void rejectsAnExplicitTitlePairWhenItsAliasesResolveToDifferentGames() {
        MemoryRepository repository = new MemoryRepository();
        FakeBgg bgg = new FakeBgg();
        BggRankedCatalogService service = new BggRankedCatalogService(repository, bgg);

        var result = service.resolveReferenceTitle("Game 10（Game 20）");

        assertThat(result).isEmpty();
        assertThat(bgg.searchQueries).isEmpty();
    }

    private static final class MemoryRepository implements BggRankedCatalogRepository {
        private Query query;

        @Override
        public Optional<Snapshot> findSnapshot() {
            return Optional.of(new Snapshot(
                    Instant.parse("2026-08-07T08:00:00Z"), LocalDate.parse("2026-08-07"), 162_686, "a".repeat(64)));
        }

        @Override
        public Page find(Query query) {
            this.query = query;
            if (query.search().equals("碁")) {
                return new Page(1, query.page(), query.size(), List.of(game(30, 30, "碁")));
            }
            return new Page(
                    162_686,
                    query.page(),
                    query.size(),
                    List.of(game(10, 10), game(20, 20)));
        }

        @Override
        public List<RankedGame> findByIds(List<Integer> bggIds) {
            return bggIds.stream()
                    .filter(id -> id == 10 || id == 20 || id == 30)
                    .map(id -> id == 30 ? game(30, 30, "碁") : game(id, id))
                    .toList();
        }

        private RankedGame game(int id, int rank) {
            return game(id, rank, "Game " + id);
        }

        private RankedGame game(int id, int rank, String name) {
            return new RankedGame(
                    id,
                    name,
                    2026,
                    rank,
                    new BigDecimal("7.1"),
                    new BigDecimal("8.1"),
                    1_000,
                    false,
                    Map.of(BggGameType.STRATEGY, rank));
        }

        @Override
        public void stage(UUID importId, List<RankedGame> games) {}

        @Override
        public void publish(UUID importId, Snapshot snapshot) {}
    }

    private static final class FakeBgg implements BoardGameGeekCatalog {
        private List<Integer> detailIds = List.of();
        private final java.util.ArrayList<String> searchQueries = new java.util.ArrayList<>();

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            searchQueries.add(query);
            return "白塔庭院".equals(query)
                    ? List.of(new SearchResult(20, "白塔庭院", 2026))
                    : List.of();
        }

        @Override
        public List<GameMatch> exactMatches(String query) {
            return List.of();
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of(new HotGame(1, 20, "Game 20", 2026, ""), new HotGame(2, 10, "Game 10", 2026, ""));
        }

        @Override
        public List<DiscoveryGame> hotGameDetails() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> gameDetails(List<Integer> bggIds) {
            detailIds = List.copyOf(bggIds);
            return bggIds.stream()
                    .map(id -> new DiscoveryGame(
                            1,
                            id,
                            "Game " + id,
                            id == 10 ? "策略十号" : "",
                            2026,
                            "",
                            2,
                            4,
                            60,
                            new BigDecimal("8.1"),
                            new BigDecimal("2.5"),
                            List.of("Strategy"),
                            List.of("Deck Building")))
                    .toList();
        }

        @Override
        public GameDetails game(int bggId) {
            return new GameDetails(
                    bggId,
                    "Game " + bggId,
                    "Players deploy agents and build their deck.",
                    "https://example.test/" + bggId + "-thumb.jpg",
                    2026,
                    2,
                    4,
                    60,
                    12,
                    "https://example.test/" + bggId + ".jpg",
                    new BigDecimal("8.1"),
                    new BigDecimal("2.5"),
                    List.of("Strategy"),
                    List.of("Worker Placement", "Deck Building"),
                    List.of("Designer"),
                    List.of("Publisher"),
                    bggId == 10 ? List.of("策略十号") : List.of());
        }
    }
}
