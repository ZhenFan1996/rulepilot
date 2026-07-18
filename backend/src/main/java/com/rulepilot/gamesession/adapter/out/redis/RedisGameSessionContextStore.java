package com.rulepilot.gamesession.adapter.out.redis;

import com.rulepilot.gamesession.application.GameSessionContextStore;
import com.rulepilot.gamesession.domain.GameSession;
import com.rulepilot.gamesession.domain.GameSessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class RedisGameSessionContextStore implements GameSessionContextStore {

    private static final String KEY_PREFIX = "rulepilot:game-session:";

    private final StringRedisTemplate redis;
    private final Duration retention;

    public RedisGameSessionContextStore(
            StringRedisTemplate redis,
            @Value("${rulepilot.game-session.context-retention:PT12H}") Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

    @Override
    public void save(GameSession session) {
        String key = key(session.id());
        redis.opsForHash().putAll(key, fields(session));
        redis.expire(key, retention);
    }

    @Override
    public Optional<GameSession> find(UUID sessionId) {
        Map<Object, Object> stored = redis.opsForHash().entries(key(sessionId));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        GameSession session = fromFields(stored);
        redis.expire(key(sessionId), retention);
        return Optional.of(session);
    }

    private Map<String, String> fields(GameSession session) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", session.id().toString());
        fields.put("gameId", session.gameId().toString());
        fields.put("editionId", session.editionId().toString());
        fields.put("documentVersionId", session.documentVersionId().toString());
        fields.put("expansionIds", session.expansionIds().stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(",")));
        fields.put("playerCount", Integer.toString(session.playerCount()));
        fields.put("roundNumber", Integer.toString(session.roundNumber()));
        fields.put("phase", session.phase());
        fields.put("activePlayer", session.activePlayer() == null ? "" : session.activePlayer().toString());
        fields.put("createdBy", session.createdBy());
        fields.put("status", session.status().name());
        fields.put("createdAt", session.createdAt().toString());
        fields.put("updatedAt", session.updatedAt().toString());
        return fields;
    }

    private GameSession fromFields(Map<Object, Object> fields) {
        String activePlayer = required(fields, "activePlayer");
        return new GameSession(
                UUID.fromString(required(fields, "id")),
                UUID.fromString(required(fields, "gameId")),
                UUID.fromString(required(fields, "editionId")),
                UUID.fromString(required(fields, "documentVersionId")),
                expansionIds(required(fields, "expansionIds")),
                Integer.parseInt(required(fields, "playerCount")),
                Integer.parseInt(required(fields, "roundNumber")),
                required(fields, "phase"),
                activePlayer.isEmpty() ? null : Integer.valueOf(activePlayer),
                required(fields, "createdBy"),
                GameSessionStatus.valueOf(required(fields, "status")),
                Instant.parse(required(fields, "createdAt")),
                Instant.parse(required(fields, "updatedAt")));
    }

    private Set<UUID> expansionIds(String value) {
        if (value.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    private String required(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("game session context field is missing: " + name);
        }
        return text;
    }

    private String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
