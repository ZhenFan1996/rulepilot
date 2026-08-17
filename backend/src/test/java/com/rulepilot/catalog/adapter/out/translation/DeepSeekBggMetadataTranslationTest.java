package com.rulepilot.catalog.adapter.out.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Request;
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

class DeepSeekBggMetadataTranslationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-07T12:34:00Z"), ZoneOffset.UTC);
    private static final Request REQUEST = new Request(
            266192,
            "展翅翱翔",
            "Build a bird reserve.",
            List.of("Animals"),
            List.of("Card Drafting"));

    @Test
    void translatesStructuredMetadataAndCachesBySourceDigestWithoutLeakingTheKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = translationServer(authorization, requestBody);
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            var adapter = adapter(redis.template(), "http://127.0.0.1:" + server.getAddress().getPort());

            var translation = adapter.translate(REQUEST);

            assertThat(translation).hasValueSatisfying(value -> {
                assertThat(value.description()).isEqualTo("建造一座鸟类保护区。");
                assertThat(value.categories()).containsExactly("动物");
                assertThat(value.mechanics()).containsExactly("卡牌轮抽");
            });
            assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            assertThat(requestBody.get())
                    .contains("\"response_format\":{\"type\":\"json_object\"}")
                    .contains("\"thinking\":{\"type\":\"disabled\"}")
                    .contains("展翅翱翔", "Build a bird reserve.", "Animals", "Card Drafting")
                    .doesNotContain("secret-key");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(redis.values()).set(
                    key.capture(), value.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofDays(30)));
            assertThat(key.getValue())
                    .startsWith("rulepilot:bgg:metadata-translation:zh-CN:v4:266192:")
                    .doesNotContain("Build a bird reserve", "Animals", "Card Drafting");
            assertThat(value.getValue()).contains("建造一座鸟类保护区。", "动物", "卡牌轮抽");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void servesValidCachedMetadataWithoutCallingTheProviderOrSpendingBudget() throws Exception {
        RedisMocks redis = redisWithMissAndBudget(1L);
        when(redis.values().get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                "description", "已緩存的中文簡介。",
                "categories", List.of("動物"),
                "mechanics", List.of("卡牌輪抽"))));
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        var translation = adapter.translate(REQUEST);

        assertThat(translation).hasValueSatisfying(value -> {
            assertThat(value.description()).isEqualTo("已缓存的中文简介。");
            assertThat(value.categories()).containsExactly("动物");
            assertThat(value.mechanics()).containsExactly("卡牌轮抽");
        });
        verify(redis.values(), never()).increment(anyString());
        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsUsableFieldsWhenAnotherOptionalTranslationFieldIsInvalid() throws Exception {
        HttpServer server = responseServer(Map.of(
                "description", "建造一座鸟类保护区。",
                "categories", List.of(),
                "mechanics", List.of("Card Drafting"),
                "providerNote", "ignored"));
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            var adapter = adapter(redis.template(), "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(adapter.translate(REQUEST)).hasValueSatisfying(value -> {
                assertThat(value.description()).isEqualTo("建造一座鸟类保护区。");
                assertThat(value.categories()).containsExactly("Animals");
                assertThat(value.mechanics()).containsExactly("Card Drafting");
            });

            verify(redis.values()).set(
                    anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsAnOversizedOptionalDescriptionButStillTranslatesStructuredTerms() throws Exception {
        String sourceDescription = "Long publisher description. ".repeat(700);
        Request request = new Request(
                266192,
                "Wingspan",
                sourceDescription,
                List.of("Animals"),
                List.of("Card Drafting"));
        HttpServer server = responseServer(Map.of(
                "description", "",
                "categories", List.of("动物"),
                "mechanics", List.of("卡牌轮抽")));
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            var adapter = adapter(redis.template(), "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(adapter.translate(request)).hasValueSatisfying(value -> {
                assertThat(value.description()).isEqualTo(sourceDescription.strip());
                assertThat(value.categories()).containsExactly("动物");
                assertThat(value.mechanics()).containsExactly("卡牌轮抽");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedBeforeTheProviderWhenTheGlobalHourlyBudgetIsExhausted() {
        RedisMocks redis = redisWithMissAndBudget(61L);
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        assertThat(adapter.translate(REQUEST)).isEmpty();

        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    private HttpServer translationServer(
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody) throws Exception {
        HttpServer server = responseServer(Map.of(
                "description", "建造一座鳥類保護區。",
                "categories", List.of("動物"),
                "mechanics", List.of("卡牌輪抽")));
        server.removeContext("/chat/completions");
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, Map.of(
                    "description", "建造一座鳥類保護區。",
                    "categories", List.of("動物"),
                    "mechanics", List.of("卡牌輪抽")));
        });
        return server;
    }

    private HttpServer responseServer(Map<String, Object> translation) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> respond(exchange, translation));
        return server;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, Map<String, Object> translation)
            throws java.io.IOException {
        ObjectMapper json = new ObjectMapper();
        String content = json.writeValueAsString(translation);
        byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop",
                "message", Map.of("content", content)))));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
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

    private DeepSeekBggMetadataTranslation adapter(StringRedisTemplate redis, String baseUrl) {
        return adapter(new OkHttpClient(), redis, baseUrl);
    }

    private DeepSeekBggMetadataTranslation adapter(
            okhttp3.Call.Factory calls,
            StringRedisTemplate redis,
            String baseUrl) {
        return new DeepSeekBggMetadataTranslation(
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
