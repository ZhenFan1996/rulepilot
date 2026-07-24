package com.rulepilot.ingestion.adapter.out.redis;

import com.rulepilot.ingestion.application.ProcessingProgressStore;
import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisProcessingProgressStore implements ProcessingProgressStore {

    private static final Duration RETENTION = Duration.ofHours(24);
    private static final String KEY_PREFIX = "rulepilot:ingestion-progress:";

    private final StringRedisTemplate redis;

    public RedisProcessingProgressStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(UUID versionId, ProgressSnapshot progress) {
        String key = key(versionId);
        redis.opsForHash().putAll(key, Map.of(
                "stage", progress.stage(),
                "percentage", Integer.toString(progress.percentage()),
                "processedPages", Integer.toString(progress.processedPages()),
                "totalPages", Integer.toString(progress.totalPages()),
                "complete", Boolean.toString(progress.complete())));
        redis.expire(key, RETENTION);
    }

    @Override
    public Optional<ProgressSnapshot> find(UUID versionId) {
        Map<Object, Object> values = redis.opsForHash().entries(key(versionId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProgressSnapshot(
                value(values, "stage"),
                Integer.parseInt(value(values, "percentage")),
                Integer.parseInt(value(values, "processedPages")),
                optionalInteger(values, "totalPages", Integer.parseInt(value(values, "processedPages"))),
                Boolean.parseBoolean(value(values, "complete"))));
    }

    private String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("processing progress is incomplete");
        }
        return value.toString();
    }

    private int optionalInteger(Map<Object, Object> values, String field, int fallback) {
        Object value = values.get(field);
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private String key(UUID versionId) {
        return KEY_PREFIX + versionId;
    }
}
