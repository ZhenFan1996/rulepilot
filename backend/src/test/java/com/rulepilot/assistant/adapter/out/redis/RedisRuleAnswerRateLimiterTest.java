package com.rulepilot.assistant.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.application.RuleAnswerRateLimitUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRuleAnswerRateLimiterTest {

    @Test
    void failsClosedWhenRedisCannotCheckTheUserLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        var limiter = new RedisRuleAnswerRateLimiter(
                redis, 20, Duration.ofMinutes(1), 1, 8, Duration.ofSeconds(30));

        assertThatThrownBy(() -> limiter.checkUser("alice"))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
