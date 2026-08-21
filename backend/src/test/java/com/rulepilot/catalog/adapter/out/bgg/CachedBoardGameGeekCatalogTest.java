package com.rulepilot.catalog.adapter.out.bgg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.application.BggMetadataCache;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CachedBoardGameGeekCatalogTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void reusesFreshHotMetadataAcrossAdapterRestartsWithoutCallingBggAgain() {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        MemoryCache cache = new MemoryCache();
        when(live.hotGames()).thenReturn(List.of(hot(42, "Catalog Game")));
        var firstProcess = adapter(live, cache);

        assertThat(firstProcess.hotGames()).extracting(HotGame::bggId).containsExactly(42);
        var restartedProcess = adapter(live, cache);
        assertThat(restartedProcess.hotGames()).extracting(HotGame::bggId).containsExactly(42);

        verify(live, times(1)).hotGames();
    }

    @Test
    void servesStaleDetailsImmediatelyAndRefreshesThemInTheBackground() {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        MemoryCache cache = new MemoryCache();
        GameDetails stale = game(42, "Cached Game");
        cache.games.put(42, new BggMetadataCache.Cached<>(
                stale, NOW.minusSeconds(1), NOW.plus(Duration.ofDays(1))));
        when(live.game(42)).thenReturn(game(42, "Fresh Game"));

        GameDetails returned = adapter(live, cache).game(42);

        assertThat(returned.name()).isEqualTo("Cached Game");
        assertThat(cache.games.get(42).value().name()).isEqualTo("Fresh Game");
        verify(live).game(42);
    }

    @Test
    void coalescesConcurrentMissesForTheSameGame() throws Exception {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        MemoryCache cache = new MemoryCache();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(live.game(42)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return game(42, "One Fetch");
        });
        var adapter = adapter(live, cache);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> adapter.game(42));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> adapter.game(42));
            Thread.sleep(50);
            release.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).name()).isEqualTo("One Fetch");
            assertThat(second.get(2, TimeUnit.SECONDS).name()).isEqualTo("One Fetch");
        }
        verify(live, times(1)).game(42);
    }

    @Test
    void fetchesOnlyMissingBatchEntriesAndPreservesRequestedOrder() {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        MemoryCache cache = new MemoryCache();
        cache.discovery.put(7, new BggMetadataCache.Cached<>(
                discovery(7, "Cached Seven"), NOW.plusSeconds(30), NOW.plusSeconds(60)));
        when(live.gameDetails(List.of(9))).thenReturn(List.of(discovery(9, "Loaded Nine")));

        List<DiscoveryGame> games = adapter(live, cache).gameDetails(List.of(9, 7));

        assertThat(games).extracting(DiscoveryGame::name).containsExactly("Loaded Nine", "Cached Seven");
        verify(live).gameDetails(List.of(9));
    }

    @Test
    void persistsFetchedCoverMetadataAcrossAdapterRestarts() {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        MemoryCache cache = new MemoryCache();
        when(live.gameDetails(List.of(9))).thenReturn(List.of(discovery(9, "Loaded Nine")));

        assertThat(adapter(live, cache).gameDetails(List.of(9)).getFirst().thumbnailUrl())
                .isEqualTo("https://example.test/9.jpg");
        assertThat(adapter(live, cache).gameDetails(List.of(9)).getFirst().thumbnailUrl())
                .isEqualTo("https://example.test/9.jpg");

        verify(live, times(1)).gameDetails(List.of(9));
        assertThat(cache.discovery).containsKey(9);
    }

    @Test
    void fallsBackToTheLiveSourceWhenThePersistentCacheCannotBeReadOrWritten() {
        BggXmlApiClient live = mock(BggXmlApiClient.class);
        BggMetadataCache unavailable = mock(BggMetadataCache.class);
        when(unavailable.game(42, NOW)).thenThrow(new IllegalStateException("database unavailable"));
        when(live.game(42)).thenReturn(game(42, "Live Game"));
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(unavailable)
                .putGame(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        GameDetails result = adapter(live, unavailable).game(42);

        assertThat(result.name()).isEqualTo("Live Game");
        verify(live).game(42);
    }

    private CachedBoardGameGeekCatalog adapter(BggXmlApiClient live, BggMetadataCache cache) {
        return new CachedBoardGameGeekCatalog(
                live,
                cache,
                Runnable::run,
                new SimpleMeterRegistry(),
                CLOCK,
                Duration.ofHours(1),
                Duration.ofDays(1),
                Duration.ofDays(7),
                Duration.ofDays(30));
    }

    private HotGame hot(int id, String name) {
        return new HotGame(1, id, name, 2026, "https://example.test/" + id + ".jpg");
    }

    private DiscoveryGame discovery(int id, String name) {
        return new DiscoveryGame(
                1,
                id,
                name,
                "",
                2026,
                "https://example.test/" + id + ".jpg",
                1,
                4,
                60,
                new BigDecimal("8.0"),
                new BigDecimal("2.5"),
                List.of("Strategy"),
                List.of("Drafting"));
    }

    private GameDetails game(int id, String name) {
        return new GameDetails(
                id,
                name,
                "Complete description.",
                "https://example.test/" + id + "-thumb.jpg",
                2026,
                1,
                4,
                60,
                10,
                "https://example.test/" + id + ".jpg",
                new BigDecimal("8.0"),
                new BigDecimal("2.5"),
                List.of("Strategy"),
                List.of("Drafting"),
                List.of("Designer"),
                List.of("Publisher"),
                List.of());
    }

    private static final class MemoryCache implements BggMetadataCache {
        private Cached<List<HotGame>> hot;
        private final Map<Integer, Cached<DiscoveryGame>> discovery = new LinkedHashMap<>();
        private final Map<Integer, Cached<GameDetails>> games = new LinkedHashMap<>();

        @Override
        public Optional<Cached<List<HotGame>>> hotGames(Instant accessedAt) {
            return Optional.ofNullable(hot);
        }

        @Override
        public Map<Integer, Cached<DiscoveryGame>> discoveryGames(List<Integer> bggIds, Instant accessedAt) {
            Map<Integer, Cached<DiscoveryGame>> found = new LinkedHashMap<>();
            bggIds.forEach(id -> Optional.ofNullable(discovery.get(id)).ifPresent(value -> found.put(id, value)));
            return found;
        }

        @Override
        public Optional<Cached<GameDetails>> game(int bggId, Instant accessedAt) {
            return Optional.ofNullable(games.get(bggId));
        }

        @Override
        public void putHotGames(List<HotGame> values, CacheWindow window) {
            hot = new Cached<>(values, window.freshUntil(), window.staleUntil());
        }

        @Override
        public void putDiscoveryGames(List<DiscoveryGame> values, CacheWindow window) {
            values.forEach(value -> discovery.put(
                    value.bggId(), new Cached<>(value, window.freshUntil(), window.staleUntil())));
        }

        @Override
        public void putGame(GameDetails value, CacheWindow window) {
            games.put(value.bggId(), new Cached<>(value, window.freshUntil(), window.staleUntil()));
        }

        @Override
        public CleanupResult prune(Instant now, int maximumEntries, long maximumBytes) {
            return new CleanupResult(0, 0);
        }
    }
}
