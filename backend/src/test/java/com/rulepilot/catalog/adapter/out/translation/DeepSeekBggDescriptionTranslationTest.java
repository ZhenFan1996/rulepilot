package com.rulepilot.catalog.adapter.out.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DeepSeekBggDescriptionTranslationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-07T12:34:00Z"), ZoneOffset.UTC);

    @Test
    void translatesWithJsonOutputAndCachesBySourceDigestWithoutLeakingTheKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = translationServer(authorization, requestBody);
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            var adapter = adapter(redis.template(), "http://127.0.0.1:" + server.getAddress().getPort());

            var translation = adapter.translate(266192, "Wingspan", "Build a bird reserve.");

            assertThat(translation).contains("建造一座鸟类保护区。");
            assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            assertThat(requestBody.get())
                    .contains("\"response_format\":{\"type\":\"json_object\"}")
                    .contains("\"thinking\":{\"type\":\"disabled\"}")
                    .contains("Wingspan", "Build a bird reserve.")
                    .doesNotContain("secret-key");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(redis.values()).set(key.capture(), org.mockito.ArgumentMatchers.eq("建造一座鸟类保护区。"),
                    org.mockito.ArgumentMatchers.eq(Duration.ofDays(30)));
            assertThat(key.getValue())
                    .startsWith("rulepilot:bgg:description-translation:zh-CN:266192:")
                    .doesNotContain("Build a bird reserve");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void servesAValidCachedTranslationWithoutCallingTheProviderOrSpendingBudget() {
        RedisMocks redis = redisWithMissAndBudget(1L);
        when(redis.values().get(anyString())).thenReturn("已缓存的中文简介。");
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        var translation = adapter.translate(266192, "Wingspan", "Build a bird reserve.");

        assertThat(translation).contains("已缓存的中文简介。");
        verify(redis.values(), never()).increment(anyString());
        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsClosedBeforeTheProviderWhenTheGlobalHourlyBudgetIsExhausted() {
        RedisMocks redis = redisWithMissAndBudget(61L);
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        assertThat(adapter.translate(266192, "Wingspan", "Build a bird reserve.")).isEmpty();

        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    private HttpServer translationServer(
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("translation", "建造一座鸟类保护区。"));
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop",
                    "message", Map.of("content", content)))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private RedisMocks redisWithMissAndBudget(long budgetCount) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(budgetCount);
        return new RedisMocks(template, values);
    }

    private DeepSeekBggDescriptionTranslation adapter(StringRedisTemplate redis, String baseUrl) {
        return adapter(new OkHttpClient(), redis, baseUrl);
    }

    private DeepSeekBggDescriptionTranslation adapter(
            okhttp3.Call.Factory calls,
            StringRedisTemplate redis,
            String baseUrl) {
        return new DeepSeekBggDescriptionTranslation(
                calls,
                new ObjectMapper(),
                redis,
                true,
                "secret-key",
                baseUrl,
                "deepseek-v4-flash",
                Duration.ofDays(30),
                60,
                2,
                CLOCK);
    }

    private record RedisMocks(StringRedisTemplate template, ValueOperations<String, String> values) {}
}
