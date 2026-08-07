package com.rulepilot.catalog.adapter.out.bgg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class BoundedExpiringCache<K, V> {

    private final int maximumEntries;
    private final Clock clock;
    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    BoundedExpiringCache(int maximumEntries) {
        this(maximumEntries, Clock.systemUTC());
    }

    BoundedExpiringCache(int maximumEntries, Clock clock) {
        if (maximumEntries < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        this.maximumEntries = maximumEntries;
        this.clock = clock;
    }

    synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(K key, V value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
        Instant now = clock.instant();
        entries.entrySet().removeIf(candidate -> !now.isBefore(candidate.getValue().expiresAt()));
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        while (entries.size() > maximumEntries) {
            K leastRecentlyUsed = entries.keySet().iterator().next();
            entries.remove(leastRecentlyUsed);
        }
    }

    synchronized int size() {
        return entries.size();
    }

    private record Entry<V>(V value, Instant expiresAt) {}
}
