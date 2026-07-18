package com.rulepilot.assistant.adapter.out.redis;

import com.rulepilot.assistant.application.RuleAnswerRateLimitExceededException;
import com.rulepilot.assistant.application.RuleAnswerRateLimitExceededException.Dimension;
import com.rulepilot.assistant.application.RuleAnswerRateLimiter;
import com.rulepilot.assistant.application.RuleAnswerRateLimitUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class RedisRuleAnswerRateLimiter implements RuleAnswerRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRuleAnswerRateLimiter.class);
    private static final long UNAVAILABLE_RETRY_SECONDS = 5;

    private static final RedisScript<List> USER_LIMIT = RedisScript.of(
            new ClassPathResource("redis/answer-user-rate-limit.lua"), List.class);
    private static final RedisScript<List> ACQUIRE_MODEL = RedisScript.of(
            new ClassPathResource("redis/answer-model-concurrency-acquire.lua"), List.class);
    private static final RedisScript<Long> RELEASE_MODEL = RedisScript.of(
            new ClassPathResource("redis/answer-model-concurrency-release.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final int userLimit;
    private final Duration userWindow;
    private final int sessionConcurrency;
    private final int providerConcurrency;
    private final Duration lease;

    public RedisRuleAnswerRateLimiter(
            StringRedisTemplate redis,
            @Value("${rulepilot.answer.rate-limit.user-requests:20}") int userLimit,
            @Value("${rulepilot.answer.rate-limit.user-window:PT1M}") Duration userWindow,
            @Value("${rulepilot.answer.rate-limit.session-concurrency:1}") int sessionConcurrency,
            @Value("${rulepilot.answer.rate-limit.provider-concurrency:8}") int providerConcurrency,
            @Value("${rulepilot.answer.rate-limit.lease:PT30S}") Duration lease) {
        if (userLimit < 1 || userWindow.isZero() || userWindow.isNegative()
                || sessionConcurrency < 1 || providerConcurrency < 1 || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("rule answer rate limit configuration is invalid");
        }
        this.redis = redis;
        this.userLimit = userLimit;
        this.userWindow = userWindow;
        this.sessionConcurrency = sessionConcurrency;
        this.providerConcurrency = providerConcurrency;
        this.lease = lease;
    }

    @Override
    public void checkUser(String username) {
        String key = "rulepilot:limit:answer:user:" + digest(required(username, "username"));
        try {
            List<?> result = redis.execute(
                    USER_LIMIT,
                    List.of(key),
                    Integer.toString(userLimit),
                    Long.toString(userWindow.toMillis()));
            requireResult(result, 2);
            if (number(result, 0) == 0) {
                throw new RuleAnswerRateLimitExceededException(Dimension.USER, retrySeconds(number(result, 1)));
            }
        } catch (RuleAnswerRateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RuleAnswerRateLimitUnavailableException(UNAVAILABLE_RETRY_SECONDS, exception);
        }
    }

    @Override
    public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
        String sessionScope = gameSessionId == null
                ? "user:" + digest(required(username, "username"))
                : "session:" + gameSessionId;
        List<String> keys = List.of(
                "rulepilot:limit:answer:concurrency:" + sessionScope,
                "rulepilot:limit:answer:concurrency:provider:" + digest(required(providerId, "provider")));
        String token = UUID.randomUUID().toString();
        try {
            List<?> result = redis.execute(
                    ACQUIRE_MODEL,
                    keys,
                    Integer.toString(sessionConcurrency),
                    Integer.toString(providerConcurrency),
                    Long.toString(lease.toMillis()),
                    token);
            requireResult(result, 3);
            if (number(result, 0) == 0) {
                Dimension dimension = number(result, 1) == 1 ? Dimension.GAME_SESSION : Dimension.MODEL_PROVIDER;
                throw new RuleAnswerRateLimitExceededException(dimension, retrySeconds(number(result, 2)));
            }
            return new RedisPermit(redis, keys, token);
        } catch (RuleAnswerRateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RuleAnswerRateLimitUnavailableException(UNAVAILABLE_RETRY_SECONDS, exception);
        }
    }

    private void requireResult(List<?> result, int expectedSize) {
        if (result == null || result.size() < expectedSize) {
            throw new IllegalStateException("rate limit script returned an invalid result");
        }
    }

    private long number(List<?> result, int index) {
        Object value = result.get(index);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("rate limit script result is invalid");
        }
        return number.longValue();
    }

    private long retrySeconds(long milliseconds) {
        return Math.max(1, (Math.max(0, milliseconds) + 999) / 1000);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class RedisPermit implements Permit {
        private final StringRedisTemplate redis;
        private final List<String> keys;
        private final String token;
        private final AtomicBoolean released = new AtomicBoolean();

        private RedisPermit(StringRedisTemplate redis, List<String> keys, String token) {
            this.redis = redis;
            this.keys = keys;
            this.token = token;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try {
                    redis.execute(RELEASE_MODEL, keys, token);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Rule answer concurrency lease release failed; the lease will expire automatically");
                }
            }
        }
    }
}
