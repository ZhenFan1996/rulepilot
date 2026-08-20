package com.rulepilot.catalog.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.application.BggMetadataCache;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class PostgresBggMetadataCache implements BggMetadataCache {

    private static final String SELECT_ENTRY = """
            SELECT payload::text AS payload, fresh_until, stale_until
            FROM bgg_metadata_cache
            WHERE cache_kind = :kind AND bgg_id = :bggId AND stale_until > :accessedAt
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int maximumEntryBytes;

    public PostgresBggMetadataCache(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper json,
            @Value("${rulepilot.bgg.cache.maximum-entry-bytes:262144}") int maximumEntryBytes) {
        if (maximumEntryBytes < 1024 || maximumEntryBytes > 524_288) {
            throw new IllegalArgumentException("BGG cache maximum entry bytes must be between 1024 and 524288");
        }
        this.jdbc = jdbc;
        this.json = json;
        this.maximumEntryBytes = maximumEntryBytes;
    }

    @Override
    public Optional<Cached<List<HotGame>>> hotGames(Instant accessedAt) {
        JavaType type = json.getTypeFactory().constructCollectionType(List.class, HotGame.class);
        return entry("HOT", 0, accessedAt, type);
    }

    @Override
    public Map<Integer, Cached<DiscoveryGame>> discoveryGames(List<Integer> bggIds, Instant accessedAt) {
        List<Integer> ids = checkedIds(bggIds);
        if (ids.isEmpty()) return Map.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("kind", "DISCOVERY")
                .addValue("ids", ids)
                .addValue("accessedAt", Timestamp.from(accessedAt));
        Map<Integer, Cached<DiscoveryGame>> found = new LinkedHashMap<>();
        List<DiscoveryCacheRow> rows = jdbc.query(
                """
                SELECT bgg_id, payload::text AS payload, fresh_until, stale_until
                FROM bgg_metadata_cache
                WHERE cache_kind = :kind AND bgg_id IN (:ids) AND stale_until > :accessedAt
                """,
                parameters,
                (result, row) -> new DiscoveryCacheRow(
                        result.getInt("bgg_id"),
                        new Cached<>(
                                read(result.getString("payload"), json.getTypeFactory().constructType(DiscoveryGame.class)),
                                result.getTimestamp("fresh_until").toInstant(),
                                result.getTimestamp("stale_until").toInstant())));
        rows.forEach(row -> found.put(row.bggId(), row.cached()));
        touch("DISCOVERY", found.keySet().stream().toList(), accessedAt);
        return Map.copyOf(found);
    }

    @Override
    public Optional<Cached<GameDetails>> game(int bggId, Instant accessedAt) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        return entry("GAME", bggId, accessedAt, json.getTypeFactory().constructType(GameDetails.class));
    }

    @Override
    public void putHotGames(List<HotGame> games, CacheWindow window) {
        put("HOT", 0, List.copyOf(games), window);
    }

    @Override
    @Transactional
    public void putDiscoveryGames(List<DiscoveryGame> games, CacheWindow window) {
        for (DiscoveryGame game : games) {
            put("DISCOVERY", game.bggId(), game, window);
            upsertOfficialChineseNames(game.bggId(), List.of(game.chineseName()), window.cachedAt());
        }
    }

    @Override
    @Transactional
    public void putGame(GameDetails game, CacheWindow window) {
        put("GAME", game.bggId(), game, window);
        upsertOfficialChineseNames(game.bggId(), game.officialChineseNames(), window.cachedAt());
    }

    @Override
    @Transactional
    public CleanupResult prune(Instant now, int maximumEntries, long maximumBytes) {
        if (maximumEntries < 1 || maximumBytes < 1024) {
            throw new IllegalArgumentException("BGG cache capacity must be positive");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("maximumEntries", maximumEntries)
                .addValue("maximumBytes", maximumBytes);
        int expired = jdbc.update("DELETE FROM bgg_metadata_cache WHERE stale_until <= :now", parameters);
        int capacity = jdbc.update(
                """
                WITH overflow AS (
                    SELECT cache_kind, bgg_id
                    FROM (
                        SELECT cache_kind,
                               bgg_id,
                               row_number() OVER (
                                   ORDER BY last_accessed_at DESC, cached_at DESC, cache_kind, bgg_id) AS position,
                               sum(payload_bytes) OVER (
                                   ORDER BY last_accessed_at DESC, cached_at DESC, cache_kind, bgg_id) AS retained_bytes
                        FROM bgg_metadata_cache
                    ) ranked
                    WHERE position > :maximumEntries OR retained_bytes > :maximumBytes
                )
                DELETE FROM bgg_metadata_cache cache
                USING overflow
                WHERE cache.cache_kind = overflow.cache_kind AND cache.bgg_id = overflow.bgg_id
                """,
                parameters);
        return new CleanupResult(expired, capacity);
    }

    private <T> Optional<Cached<T>> entry(String kind, int bggId, Instant accessedAt, JavaType type) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("kind", kind)
                .addValue("bggId", bggId)
                .addValue("accessedAt", Timestamp.from(accessedAt));
        Optional<Cached<T>> found = jdbc.<Cached<T>>query(
                        SELECT_ENTRY,
                        parameters,
                        (result, row) -> {
                            T value = read(result.getString("payload"), type);
                            return new Cached<>(
                                    value,
                                    result.getTimestamp("fresh_until").toInstant(),
                                    result.getTimestamp("stale_until").toInstant());
                        })
                .stream()
                .findFirst();
        if (found.isPresent()) touch(kind, List.of(bggId), accessedAt);
        return found;
    }

    private void touch(String kind, List<Integer> ids, Instant accessedAt) {
        if (ids.isEmpty()) return;
        jdbc.update(
                """
                UPDATE bgg_metadata_cache
                SET last_accessed_at = GREATEST(last_accessed_at, :accessedAt)
                WHERE cache_kind = :kind AND bgg_id IN (:ids)
                  AND last_accessed_at < :touchBefore
                """,
                new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("ids", ids)
                        .addValue("accessedAt", Timestamp.from(accessedAt))
                        .addValue("touchBefore", Timestamp.from(accessedAt.minus(java.time.Duration.ofMinutes(15)))));
    }

    private void put(String kind, int bggId, Object value, CacheWindow window) {
        String payload = write(value);
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > maximumEntryBytes) {
            throw new IllegalArgumentException("BGG cache entry exceeds the configured byte limit");
        }
        jdbc.update(
                """
                INSERT INTO bgg_metadata_cache (
                    cache_kind, bgg_id, payload, payload_bytes, cached_at, fresh_until, stale_until, last_accessed_at)
                VALUES (:kind, :bggId, CAST(:payload AS jsonb), :payloadBytes, :cachedAt, :freshUntil, :staleUntil, :cachedAt)
                ON CONFLICT (cache_kind, bgg_id) DO UPDATE SET
                    payload = EXCLUDED.payload,
                    payload_bytes = EXCLUDED.payload_bytes,
                    cached_at = EXCLUDED.cached_at,
                    fresh_until = EXCLUDED.fresh_until,
                    stale_until = EXCLUDED.stale_until,
                    last_accessed_at = EXCLUDED.last_accessed_at
                """,
                new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("bggId", bggId)
                        .addValue("payload", payload)
                        .addValue("payloadBytes", payloadBytes)
                        .addValue("cachedAt", Timestamp.from(window.cachedAt()))
                        .addValue("freshUntil", Timestamp.from(window.freshUntil()))
                        .addValue("staleUntil", Timestamp.from(window.staleUntil())));
    }

    private void upsertOfficialChineseNames(int bggId, List<String> names, Instant observedAt) {
        MapSqlParameterSource[] aliases = names.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .distinct()
                .map(name -> new MapSqlParameterSource()
                        .addValue("bggId", bggId)
                        .addValue("alias", name)
                        .addValue("locale", "zh")
                        .addValue("source", "BGG_OFFICIAL_VERSION")
                        .addValue("observedAt", Timestamp.from(observedAt)))
                .toArray(MapSqlParameterSource[]::new);
        if (aliases.length == 0) return;
        jdbc.batchUpdate(
                """
                INSERT INTO bgg_game_name_alias (bgg_id, alias, locale, source, observed_at)
                VALUES (:bggId, :alias, :locale, :source, :observedAt)
                ON CONFLICT (bgg_id, alias, locale) DO UPDATE SET
                    source = EXCLUDED.source,
                    observed_at = GREATEST(bgg_game_name_alias.observed_at, EXCLUDED.observed_at)
                """,
                aliases);
    }

    private List<Integer> checkedIds(List<Integer> values) {
        if (values == null) return List.of();
        List<Integer> ids = values.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException("BGG ids must be positive");
        return ids;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGG cache payload could not be encoded", exception);
        }
    }

    private <T> T read(String payload, JavaType type) {
        try {
            return json.readValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGG cache payload could not be decoded", exception);
        }
    }

    private record DiscoveryCacheRow(int bggId, Cached<DiscoveryGame> cached) {}
}
