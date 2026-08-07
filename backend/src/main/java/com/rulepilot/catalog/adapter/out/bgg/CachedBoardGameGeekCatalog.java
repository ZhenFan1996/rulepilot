package com.rulepilot.catalog.adapter.out.bgg;

import com.rulepilot.catalog.application.BggMetadataCache;
import com.rulepilot.catalog.application.BggMetadataCache.CacheWindow;
import com.rulepilot.catalog.application.BggMetadataCache.Cached;
import com.rulepilot.catalog.application.BoardGameGeekCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("!test")
public class CachedBoardGameGeekCatalog implements BoardGameGeekCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedBoardGameGeekCatalog.class);

    private final BggXmlApiClient live;
    private final BggMetadataCache cache;
    private final TaskExecutor refreshExecutor;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Duration hotFreshTtl;
    private final Duration hotStaleTtl;
    private final Duration detailFreshTtl;
    private final Duration detailStaleTtl;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public CachedBoardGameGeekCatalog(
            BggXmlApiClient live,
            BggMetadataCache cache,
            @Qualifier("bggCacheRefreshExecutor") TaskExecutor refreshExecutor,
            MeterRegistry metrics,
            @Value("${rulepilot.bgg.cache.hot-fresh-ttl:PT1H}") Duration hotFreshTtl,
            @Value("${rulepilot.bgg.cache.hot-stale-ttl:P1D}") Duration hotStaleTtl,
            @Value("${rulepilot.bgg.cache.detail-fresh-ttl:P7D}") Duration detailFreshTtl,
            @Value("${rulepilot.bgg.cache.detail-stale-ttl:P30D}") Duration detailStaleTtl) {
        this(
                live,
                cache,
                refreshExecutor,
                metrics,
                Clock.systemUTC(),
                hotFreshTtl,
                hotStaleTtl,
                detailFreshTtl,
                detailStaleTtl);
    }

    CachedBoardGameGeekCatalog(
            BggXmlApiClient live,
            BggMetadataCache cache,
            TaskExecutor refreshExecutor,
            MeterRegistry metrics,
            Clock clock,
            Duration hotFreshTtl,
            Duration hotStaleTtl,
            Duration detailFreshTtl,
            Duration detailStaleTtl) {
        this.live = live;
        this.cache = cache;
        this.refreshExecutor = refreshExecutor;
        this.metrics = metrics;
        this.clock = clock;
        this.hotFreshTtl = checkedTtl(hotFreshTtl, "hot fresh TTL");
        this.hotStaleTtl = checkedStaleTtl(hotStaleTtl, this.hotFreshTtl, "hot stale TTL");
        this.detailFreshTtl = checkedTtl(detailFreshTtl, "detail fresh TTL");
        this.detailStaleTtl = checkedStaleTtl(detailStaleTtl, this.detailFreshTtl, "detail stale TTL");
    }

    @Override
    public boolean configured() {
        return live.configured();
    }

    @Override
    public List<SearchResult> search(String query) {
        return live.search(query);
    }

    @Override
    public List<GameMatch> exactMatches(String query) {
        return live.exactMatches(query);
    }

    @Override
    public List<HotGame> hotGames() {
        Instant now = clock.instant();
        Optional<Cached<List<HotGame>>> cached = safeHotGames(now);
        if (cached.isPresent() && cached.get().freshAt(now)) {
            recordRequest("hot", "hit");
            return cached.get().value();
        }
        if (cached.isPresent()) {
            recordRequest("hot", "stale");
            refreshAsync("hot", this::loadAndCacheHotGames);
            return cached.get().value();
        }
        recordRequest("hot", "miss");
        return coalesced("hot", this::loadAndCacheHotGames);
    }

    @Override
    public List<DiscoveryGame> hotGameDetails() {
        List<HotGame> hot = hotGames().stream().filter(game -> !game.thumbnailUrl().isBlank()).limit(12).toList();
        if (hot.isEmpty()) return List.of();
        Map<Integer, DiscoveryGame> byId = gameDetails(hot.stream().map(HotGame::bggId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        DiscoveryGame::bggId, game -> game, (first, ignored) -> first, LinkedHashMap::new));
        return hot.stream()
                .map(candidate -> ranked(candidate, byId.get(candidate.bggId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<DiscoveryGame> gameDetails(List<Integer> bggIds) {
        List<Integer> ids = checkedIds(bggIds);
        if (ids.isEmpty()) return List.of();
        Instant now = clock.instant();
        Map<Integer, Cached<DiscoveryGame>> cached = safeDiscoveryGames(ids, now);
        List<Integer> missing = new ArrayList<>();
        List<Integer> stale = new ArrayList<>();
        for (Integer id : ids) {
            Cached<DiscoveryGame> entry = cached.get(id);
            if (entry == null) {
                missing.add(id);
                recordRequest("discovery", "miss");
            } else if (entry.freshAt(now)) {
                recordRequest("discovery", "hit");
            } else {
                stale.add(id);
                recordRequest("discovery", "stale");
            }
        }
        Map<Integer, DiscoveryGame> available = cached.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().value(), (first, ignored) -> first, LinkedHashMap::new));
        if (!missing.isEmpty()) {
            for (DiscoveryGame loaded : coalesced(
                    discoveryKey(missing), () -> loadAndCacheDiscoveryGames(missing))) {
                available.put(loaded.bggId(), loaded);
            }
        }
        if (!stale.isEmpty()) {
            refreshAsync(discoveryKey(stale), () -> loadAndCacheDiscoveryGames(stale));
        }
        return ids.stream().map(available::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public GameDetails game(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        Instant now = clock.instant();
        Optional<Cached<GameDetails>> cached = safeGame(bggId, now);
        if (cached.isPresent() && cached.get().freshAt(now)) {
            recordRequest("game", "hit");
            return cached.get().value();
        }
        if (cached.isPresent()) {
            recordRequest("game", "stale");
            refreshAsync("game:" + bggId, () -> loadAndCacheGame(bggId));
            return cached.get().value();
        }
        recordRequest("game", "miss");
        return coalesced("game:" + bggId, () -> loadAndCacheGame(bggId));
    }

    private List<HotGame> loadAndCacheHotGames() {
        List<HotGame> games = live.hotGames();
        safeWrite(() -> cache.putHotGames(games, window(hotFreshTtl, hotStaleTtl)));
        return games;
    }

    private List<DiscoveryGame> loadAndCacheDiscoveryGames(List<Integer> ids) {
        List<DiscoveryGame> games = live.gameDetails(ids);
        safeWrite(() -> cache.putDiscoveryGames(games, window(detailFreshTtl, detailStaleTtl)));
        return games;
    }

    private GameDetails loadAndCacheGame(int bggId) {
        GameDetails game = live.game(bggId);
        safeWrite(() -> cache.putGame(game, window(detailFreshTtl, detailStaleTtl)));
        return game;
    }

    private Optional<Cached<List<HotGame>>> safeHotGames(Instant now) {
        try {
            return cache.hotGames(now);
        } catch (RuntimeException exception) {
            cacheError("read");
            return Optional.empty();
        }
    }

    private Map<Integer, Cached<DiscoveryGame>> safeDiscoveryGames(List<Integer> ids, Instant now) {
        try {
            return cache.discoveryGames(ids, now);
        } catch (RuntimeException exception) {
            cacheError("read");
            return Map.of();
        }
    }

    private Optional<Cached<GameDetails>> safeGame(int bggId, Instant now) {
        try {
            return cache.game(bggId, now);
        } catch (RuntimeException exception) {
            cacheError("read");
            return Optional.empty();
        }
    }

    private void safeWrite(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException exception) {
            cacheError("write");
        }
    }

    private CacheWindow window(Duration freshTtl, Duration staleTtl) {
        Instant now = clock.instant();
        return new CacheWindow(now, now.plus(freshTtl), now.plus(staleTtl));
    }

    private void refreshAsync(String key, Supplier<?> load) {
        CompletableFuture<Object> pending = new CompletableFuture<>();
        if (inFlight.putIfAbsent(key, pending) != null) return;
        try {
            refreshExecutor.execute(() -> complete(key, pending, load));
        } catch (RuntimeException exception) {
            inFlight.remove(key, pending);
            recordRefresh("rejected");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T coalesced(String key, Supplier<T> load) {
        CompletableFuture<Object> pending = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) return (T) join(existing);
        complete(key, pending, load);
        return (T) join(pending);
    }

    private void complete(String key, CompletableFuture<Object> pending, Supplier<?> load) {
        try {
            pending.complete(load.get());
            recordRefresh("success");
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            recordRefresh("failure");
            LOGGER.warn("BGG cache refresh failed for {}", key);
        } finally {
            inFlight.remove(key, pending);
        }
    }

    private Object join(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private DiscoveryGame ranked(HotGame hot, DiscoveryGame game) {
        if (game == null) return null;
        return new DiscoveryGame(
                hot.rank(),
                game.bggId(),
                game.name(),
                game.chineseName(),
                game.publicationYear(),
                game.thumbnailUrl().isBlank() ? hot.thumbnailUrl() : game.thumbnailUrl(),
                game.minPlayers(),
                game.maxPlayers(),
                game.playingTimeMinutes(),
                game.averageRating(),
                game.averageWeight(),
                game.categories(),
                game.mechanics());
    }

    private List<Integer> checkedIds(List<Integer> values) {
        List<Integer> ids = values == null
                ? List.of()
                : values.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.size() > 20 || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("BGG batch details require at most 20 positive ids");
        }
        return ids;
    }

    private String discoveryKey(List<Integer> ids) {
        return "discovery:" + ids.stream().sorted(Comparator.naturalOrder()).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Duration checkedTtl(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("BGG cache " + label + " must be positive");
        }
        return value;
    }

    private Duration checkedStaleTtl(Duration value, Duration fresh, String label) {
        Duration checked = checkedTtl(value, label);
        if (checked.compareTo(fresh) < 0) {
            throw new IllegalArgumentException("BGG cache " + label + " cannot be shorter than the fresh TTL");
        }
        return checked;
    }

    private void recordRequest(String kind, String result) {
        metrics.counter("rulepilot.bgg.cache.requests", "kind", kind, "result", result).increment();
    }

    private void recordRefresh(String result) {
        metrics.counter("rulepilot.bgg.cache.refresh", "result", result).increment();
    }

    private void cacheError(String operation) {
        metrics.counter("rulepilot.bgg.cache.errors", "operation", operation).increment();
        LOGGER.warn("BGG metadata cache {} failed; using the live source", operation);
    }
}
