package com.rulepilot.catalog.adapter.out.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import com.rulepilot.catalog.BggMetadataTranslation.Request;
import com.rulepilot.catalog.BggMetadataTranslation.PrewarmStatus;
import com.rulepilot.catalog.application.BggMetadataTranslationStore;
import com.rulepilot.catalog.application.BggMetadataTranslationStore.Key;
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
    void prewarmMaterializesStructuredMetadataAndCachesByVersionedSourceIdentity() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = translationServer(authorization, requestBody);
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            MemoryTranslationStore store = new MemoryTranslationStore();
            var adapter = adapter(
                    redis.template(), store, true, "http://127.0.0.1:" + server.getAddress().getPort());

            var result = adapter.prewarm(REQUEST);

            assertThat(result.status()).isEqualTo(PrewarmStatus.READY);
            assertThat(store.values.values()).singleElement().satisfies(value -> {
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
                    .startsWith("rulepilot:bgg:metadata-translation:zh-CN:v5:266192:")
                    .doesNotContain("Build a bird reserve", "Animals", "Card Drafting");
            assertThat(value.getValue()).contains("建造一座鸟类保护区。", "动物", "卡牌轮抽");
            assertThat(store.values).hasSize(1).allSatisfy((storedKey, stored) -> {
                assertThat(storedKey.bggId()).isEqualTo(266192);
                assertThat(storedKey.locale()).isEqualTo("zh-CN");
                assertThat(storedKey.contractVersion()).isEqualTo(5);
                assertThat(stored.description()).isEqualTo("建造一座鸟类保护区。");
            });
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

        var translation = adapter.readStored(REQUEST);

        assertThat(translation).hasValueSatisfying(value -> {
            assertThat(value.description()).isEqualTo("已缓存的中文简介。");
            assertThat(value.categories()).containsExactly("动物");
            assertThat(value.mechanics()).containsExactly("卡牌轮抽");
        });
        verify(redis.values(), never()).increment(anyString());
        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void servesDurableMetadataAfterRestartEvenWhenTheProviderIsDisabled() throws Exception {
        HttpServer server = responseServer(Map.of(
                "description", "持久化中文简介。",
                "categories", List.of("动物"),
                "mechanics", List.of("卡牌轮抽")));
        server.start();
        try {
            MemoryTranslationStore store = new MemoryTranslationStore();
            RedisMocks initialRedis = redisWithMissAndBudget(1L);
            assertThat(adapter(
                                    initialRedis.template(),
                                    store,
                                    true,
                                    "http://127.0.0.1:" + server.getAddress().getPort())
                            .prewarm(REQUEST)
                            .status())
                    .isEqualTo(PrewarmStatus.READY);

            RedisMocks restartedRedis = redisWithMissAndBudget(1L);
            OkHttpClient unavailableProvider = mock(OkHttpClient.class);
            var restarted = adapter(
                    unavailableProvider,
                    restartedRedis.template(),
                    store,
                    false,
                    "http://provider.invalid");

            assertThat(restarted.readStored(REQUEST)).hasValueSatisfying(value ->
                    assertThat(value.description()).isEqualTo("持久化中文简介。"));
            verify(restartedRedis.values(), never()).increment(anyString());
            verify(unavailableProvider, never()).newCall(org.mockito.ArgumentMatchers.any());
            verify(restartedRedis.values()).set(
                    anyString(), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofDays(30)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsAndPersistsAValidTranslationWhenTheRedisWriteFails() throws Exception {
        HttpServer server = responseServer(Map.of(
                "description", "可用中文简介。",
                "categories", List.of("动物"),
                "mechanics", List.of("卡牌轮抽")));
        server.start();
        try {
            RedisMocks redis = redisWithMissAndBudget(1L);
            doThrow(new IllegalStateException("redis unavailable"))
                    .when(redis.values())
                    .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
            MemoryTranslationStore store = new MemoryTranslationStore();

            var result = adapter(
                            redis.template(),
                            store,
                            true,
                            "http://127.0.0.1:" + server.getAddress().getPort())
                    .prewarm(REQUEST);

            assertThat(result.status()).isEqualTo(PrewarmStatus.READY);
            assertThat(store.values.values()).singleElement().satisfies(value ->
                    assertThat(value.description()).isEqualTo("可用中文简介。"));
        } finally {
            server.stop(0);
        }
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
            MemoryTranslationStore store = new MemoryTranslationStore();
            var adapter = adapter(
                    redis.template(), store, true, "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(adapter.prewarm(REQUEST).status()).isEqualTo(PrewarmStatus.READY);
            assertThat(store.values.values()).singleElement().satisfies(value -> {
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
            MemoryTranslationStore store = new MemoryTranslationStore();
            var adapter = adapter(
                    redis.template(), store, true, "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(adapter.prewarm(request).status()).isEqualTo(PrewarmStatus.READY);
            assertThat(store.values.values()).singleElement().satisfies(value -> {
                assertThat(value.description()).isEqualTo(sourceDescription.strip());
                assertThat(value.categories()).containsExactly("动物");
                assertThat(value.mechanics()).containsExactly("卡牌轮抽");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interactiveReadMissNeverSpendsBudgetOrCallsTheProvider() {
        RedisMocks redis = redisWithMissAndBudget(1L);
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        assertThat(adapter.readStored(REQUEST)).isEmpty();

        verify(redis.values(), never()).increment(anyString());
        verify(calls, never()).newCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reportsWhyPrewarmPausedWithoutExposingProviderDetails() {
        RedisMocks redis = redisWithMissAndBudget(61L);
        OkHttpClient calls = mock(OkHttpClient.class);
        var adapter = adapter(calls, redis.template(), "http://provider.invalid");

        assertThat(adapter.prewarm(REQUEST).status()).isEqualTo(PrewarmStatus.RETRY_HOURLY_BUDGET);
        assertThat(adapter.prewarm(new Request(0, "", "", List.of(), List.of())).status())
                .isEqualTo(PrewarmStatus.SKIPPED_INVALID_SOURCE);

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
        return adapter(redis, new MemoryTranslationStore(), true, baseUrl);
    }

    private DeepSeekBggMetadataTranslation adapter(
            StringRedisTemplate redis,
            BggMetadataTranslationStore store,
            boolean enabled,
            String baseUrl) {
        return adapter(new OkHttpClient(), redis, store, enabled, baseUrl);
    }

    private DeepSeekBggMetadataTranslation adapter(
            okhttp3.Call.Factory calls,
            StringRedisTemplate redis,
            String baseUrl) {
        return adapter(calls, redis, new MemoryTranslationStore(), true, baseUrl);
    }

    private DeepSeekBggMetadataTranslation adapter(
            okhttp3.Call.Factory calls,
            StringRedisTemplate redis,
            BggMetadataTranslationStore store,
            boolean enabled,
            String baseUrl) {
        return new DeepSeekBggMetadataTranslation(
                calls,
                new ObjectMapper(),
                redis,
                store,
                enabled,
                "secret-key",
                baseUrl,
                "deepseek-v4-flash",
                Duration.ofDays(30),
                60,
                2,
                CLOCK);
    }

    private record RedisMocks(StringRedisTemplate template, ValueOperations<String, String> values) {}

    private static final class MemoryTranslationStore implements BggMetadataTranslationStore {
        private final Map<Key, Translation> values = new java.util.LinkedHashMap<>();

        @Override
        public java.util.Optional<Translation> find(Key key) {
            return java.util.Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(Key key, Translation translation, Instant translatedAt) {
            values.put(key, translation);
        }
    }
}
