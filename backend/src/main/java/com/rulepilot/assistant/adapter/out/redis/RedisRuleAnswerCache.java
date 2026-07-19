package com.rulepilot.assistant.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.application.RuleAnswerCache;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class RedisRuleAnswerCache implements RuleAnswerCache {

    private static final String KEY_PREFIX = "rulepilot:answer:pipeline-v13:data-v";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration retention;
    private final Duration retentionJitter;

    public RedisRuleAnswerCache(
            StringRedisTemplate redis,
            @Value("${rulepilot.answer.cache-retention:PT6H}") Duration retention,
            @Value("${rulepilot.answer.cache-retention-jitter:PT1H}") Duration retentionJitter) {
        if (retention.isZero() || retention.isNegative() || retentionJitter.isNegative()) {
            throw new IllegalArgumentException("answer cache retention must be positive");
        }
        this.redis = redis;
        this.retention = retention;
        this.retentionJitter = retentionJitter;
    }

    @Override
    public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
        String value = redis.opsForValue().get(redisKey(key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            StructuredRuleAnswer answer = json.readValue(value, StructuredRuleAnswer.class);
            if (!answer.documentVersionId().equals(key.documentVersionId()) || answer.status() != AnswerStatus.ANSWERED) {
                throw new IllegalStateException("cached answer scope is invalid");
            }
            return Optional.of(answer);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cached answer is invalid", exception);
        }
    }

    @Override
    public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
        if (answer.status() != AnswerStatus.ANSWERED || !answer.documentVersionId().equals(key.documentVersionId())) {
            throw new IllegalArgumentException("only a validated version-matched answer can be cached");
        }
        try {
            redis.opsForValue().set(redisKey(key), json.writeValueAsString(answer), retentionWithJitter());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("answer cache serialization failed", exception);
        }
    }

    private Duration retentionWithJitter() {
        long jitterSeconds = retentionJitter.toSeconds();
        if (jitterSeconds == 0) {
            return retention;
        }
        return retention.plusSeconds(ThreadLocalRandom.current().nextLong(jitterSeconds + 1));
    }

    private String redisKey(AnswerCacheKey key) {
        String canonical = String.join("\u001f",
                key.normalizedQuestion(),
                value(key.currentLessonSection()),
                value(key.gamePhase()),
                key.playerCount() == null ? "" : key.playerCount().toString(),
                key.activeExpansions().stream().map(java.util.UUID::toString).sorted().collect(Collectors.joining(",")));
        return KEY_PREFIX + key.ruleDataVersion() + ":" + key.documentVersionId() + ":" + sha256(canonical);
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
