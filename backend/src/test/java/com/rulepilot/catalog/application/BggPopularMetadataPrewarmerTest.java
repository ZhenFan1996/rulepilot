package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class BggPopularMetadataPrewarmerTest {

    @Test
    void hydratesTheConfiguredRankPrefixInBggSizedBatchesWithoutLimitingRecommendationRecall() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(60);
        RecordingBgg bgg = new RecordingBgg();
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked, bgg, new SyncTaskExecutor(), true, 45);

        prewarmer.prewarm();

        assertThat(bgg.batches).hasSize(3);
        assertThat(bgg.batches.get(0)).containsExactlyElementsOf(ids(1, 20));
        assertThat(bgg.batches.get(1)).containsExactlyElementsOf(ids(21, 40));
        assertThat(bgg.batches.get(2)).containsExactlyElementsOf(ids(41, 45));
        assertThat(ranked.queries).allSatisfy(query -> assertThat(query.size()).isEqualTo(20));
    }

    private static List<Integer> ids(int first, int last) {
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static final class MemoryRankedCatalog implements BggRankedCatalogRepository {
        private final int count;
        private final List<Query> queries = new ArrayList<>();

        private MemoryRankedCatalog(int count) {
            this.count = count;
        }

        @Override
        public Optional<Snapshot> findSnapshot() {
            return Optional.empty();
        }

        @Override
        public Page find(Query query) {
            queries.add(query);
            int start = query.page() * query.size() + 1;
            List<RankedGame> games = java.util.stream.IntStream.range(start, Math.min(start + query.size(), count + 1))
                    .mapToObj(id -> new RankedGame(
                            id, "Game " + id, 2026, id, BigDecimal.ONE, BigDecimal.ONE, 100, false,
                            Map.of(BggGameType.STRATEGY, id)))
                    .toList();
            return new Page(count, query.page(), query.size(), games);
        }

        @Override
        public void stage(UUID importId, List<RankedGame> games) {}

        @Override
        public void publish(UUID importId, Snapshot snapshot) {}
    }

    private static final class RecordingBgg implements BoardGameGeekCatalog {
        private final List<List<Integer>> batches = new ArrayList<>();

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            return List.of();
        }

        @Override
        public List<GameMatch> exactMatches(String query) {
            return List.of();
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> hotGameDetails() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> gameDetails(List<Integer> bggIds) {
            batches.add(List.copyOf(bggIds));
            return List.of();
        }

        @Override
        public GameDetails game(int bggId) {
            throw new UnsupportedOperationException();
        }
    }
}
