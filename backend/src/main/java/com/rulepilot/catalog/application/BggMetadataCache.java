package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BggMetadataCache {

    Optional<Cached<List<HotGame>>> hotGames(Instant accessedAt);

    Map<Integer, Cached<DiscoveryGame>> discoveryGames(List<Integer> bggIds, Instant accessedAt);

    Optional<Cached<GameDetails>> game(int bggId, Instant accessedAt);

    void putHotGames(List<HotGame> games, CacheWindow window);

    void putDiscoveryGames(List<DiscoveryGame> games, CacheWindow window);

    void putGame(GameDetails game, CacheWindow window);

    CleanupResult prune(Instant now, int maximumEntries, long maximumBytes);

    record Cached<T>(T value, Instant freshUntil, Instant staleUntil) {
        public Cached {
            if (value == null || freshUntil == null || staleUntil == null || staleUntil.isBefore(freshUntil)) {
                throw new IllegalArgumentException("BGG cache entry is invalid");
            }
        }

        public boolean freshAt(Instant now) {
            return now.isBefore(freshUntil);
        }
    }

    record CacheWindow(Instant cachedAt, Instant freshUntil, Instant staleUntil) {
        public CacheWindow {
            if (cachedAt == null
                    || freshUntil == null
                    || staleUntil == null
                    || freshUntil.isBefore(cachedAt)
                    || staleUntil.isBefore(freshUntil)) {
                throw new IllegalArgumentException("BGG cache window is invalid");
            }
        }
    }

    record CleanupResult(int expiredEntries, int capacityEntries) {
        public CleanupResult {
            if (expiredEntries < 0 || capacityEntries < 0) {
                throw new IllegalArgumentException("BGG cache cleanup counts cannot be negative");
            }
        }

        public int totalEntries() {
            return expiredEntries + capacityEntries;
        }
    }
}
