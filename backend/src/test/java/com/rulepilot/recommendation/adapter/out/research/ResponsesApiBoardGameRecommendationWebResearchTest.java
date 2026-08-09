package com.rulepilot.recommendation.adapter.out.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Request;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
import com.rulepilot.catalog.BggGameType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ResponsesApiBoardGameRecommendationWebResearchTest {

    @Test
    void rejectsLegacyQwenPlusBeforeCreatingAWebSearchRequest() {
        var calls = mock(okhttp3.Call.Factory.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> new ResponsesApiBoardGameRecommendationWebResearch(
                        calls,
                        new ObjectMapper(),
                        redis,
                        true,
                        "secret-test-key",
                        "https://dashscope.aliyuncs.com/api/v1",
                        "qwen-plus-latest",
                        Duration.ofDays(7),
                        20,
                        2,
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen-plus")
                .hasMessageContaining("prohibited");
        org.mockito.Mockito.verifyNoInteractions(calls);
    }

    @Test
    void surfacesATransportFailureSoTheAgentCanOpenItsPerRunCircuitBreaker() throws Exception {
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);
        okhttp3.Call call = mock(okhttp3.Call.class);
        when(calls.newCall(any(okhttp3.Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("synthetic transport failure"));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(1L);
        var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                calls,
                new ObjectMapper(),
                redis,
                true,
                "secret-test-key",
                "https://research.example/v1",
                "research-model",
                Duration.ofDays(7),
                20,
                2,
                Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

        DiscoveryRequest request = new DiscoveryRequest(
                "synthetic low-conflict spatial games",
                List.of(BggGameType.ABSTRACT),
                "en");
        assertThatThrownBy(() -> adapter.discover(request))
                .isInstanceOf(WebResearchUnavailableException.class)
                .hasMessage("PROVIDER_IO_ERROR");
        assertThatThrownBy(() -> adapter.discover(request))
                .isInstanceOf(WebResearchUnavailableException.class)
                .hasMessage("PROVIDER_BACKOFF");
        org.mockito.Mockito.verify(call, org.mockito.Mockito.times(1)).execute();
    }

    @Test
    void usesTheStandardWebSearchToolAndReturnsOnlyValidatedHttpsSources() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {
                      "output": [
                        {"type":"web_search_call","action":{"sources":[
                          {"title":"Publisher guide","url":"https://publisher.example/games/10"},
                          {"title":"Unsafe source","url":"http://unsafe.example/games/10"},
                          {"url":"https://reviews.example/3"},
                          {"url":"https://reviews.example/4"},
                          {"url":"https://reviews.example/5"},
                          {"url":"https://reviews.example/6"},
                          {"url":"https://reviews.example/7"},
                          {"url":"https://reviews.example/8"},
                          {"url":"https://reviews.example/9"},
                          {"url":"https://reviews.example/10"},
                          {"url":"https://reviews.example/11"},
                          {"url":"https://reviews.example/12"},
                          {"url":"https://reviews.example/13"},
                          {"url":"https://reviews.example/14"},
                          {"title":"Experienced players","url":"https://community.example/games/10"}
                        ]}},
                        {"type":"message","content":[{"type":"output_text","text":"{\\"games\\":[{\\"bggId\\":10,\\"observations\\":[{\\"text\\":\\"The publisher describes a short guided teach.\\",\\"sourceIndexes\\":[1,15]}]}]}"}]}
                      ]
                    }
                    """);
        });
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(),
                    json,
                    redis,
                    true,
                    "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model",
                    Duration.ofDays(7),
                    20,
                    2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var result = adapter.research(new Request(
                    List.of(candidate(10)),
                    "en"));

            assertThat(result).hasValueSatisfying(research -> {
                assertThat(research.sources()).extracting(source -> source.index()).containsExactly(1, 2);
                assertThat(research.sources()).extracting(source -> source.domain())
                        .containsExactly("publisher.example", "community.example");
                assertThat(research.sources()).allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
                assertThat(research.games()).singleElement().satisfies(game -> {
                    assertThat(game.bggId()).isEqualTo(10);
                    assertThat(game.observations().getFirst().sourceIndexes()).containsExactly(1, 2);
                });
            });
            JsonNode sent = json.readTree(body.get());
            assertThat(sent.path("tools")).singleElement().satisfies(tool ->
                    assertThat(tool.path("type").asText()).isEqualTo("web_search"));
            assertThat(sent.path("tool_choice").asText()).isEqualTo("required");
            assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("minimal");
            assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(1_600);
            assertThat(sent.path("store").asBoolean()).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer secret-test-key");
            assertThat(body.get()).doesNotContain("secret-test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversSourceBackedTitleHypothesesWithoutDoingBggIdentityResolution() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {
                      "output": [
                        {"type":"web_search_call","action":{"sources":[
                          {"title":"BGG item","url":"https://boardgamegeek.com/boardgame/60/example"}
                        ]}},
                        {"type":"message","content":[{"type":"output_text","text":"{\\\"candidates\\\":[{\\\"name\\\":\\\"Example Game\\\",\\\"fitObservation\\\":\\\"The search result describes the requested table experience.\\\",\\\"sourceIndexes\\\":[1]}]}"}]}
                      ]
                    }
                    """);
        });
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), json, redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var result = adapter.discover(new DiscoveryRequest(
                    "Science Fiction games with a shared investigation and low conflict",
                    List.of(BggGameType.THEMATIC),
                    "zh-CN"));

            assertThat(result).hasValueSatisfying(discovery -> {
                assertThat(discovery.candidates()).singleElement().satisfies(candidate -> {
                    assertThat(candidate.name()).isEqualTo("Example Game");
                    assertThat(candidate.fitObservation()).contains("requested table experience");
                    assertThat(candidate.sourceIndexes()).containsExactly(1);
                });
                assertThat(discovery.sources()).hasSize(1);
            });
            JsonNode sent = json.readTree(body.get());
            assertThat(sent.path("tool_choice").asText()).isEqualTo("required");
            assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("none");
            assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(700);
            assertThat(sent.path("input").asText())
                    .contains("Run exactly one broad web search", "Science Fiction", "THEMATIC")
                    .doesNotContain("\"bggId\"");
            assertThat(body.get()).doesNotContain("科幻主题", "secret-test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void researchesOnlyTheFocusedQuestionWithoutReceivingThePrivateTranscript() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"output":[
                      {"type":"web_search_call","action":{"sources":[
                        {"title":"Publisher rules","url":"https://publisher.example/rules"}]}},
                      {"type":"message","content":[{"type":"output_text","text":"{\\"games\\":[{\\"bggId\\":10,\\"observations\\":[{\\"text\\":\\"Each round alternates agent turns before a reveal turn.\\",\\"sourceIndexes\\":[1]}]}]}"}]}
                    ]}
                    """);
        });
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), json, redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var result = adapter.research(new Request(
                    List.of(candidate(10)), "zh-CN", "这款游戏一轮具体怎么玩？"));

            assertThat(result).isPresent();
            assertThat(body.get())
                    .contains("这款游戏一轮具体怎么玩？", "Answer only the supplied question")
                    .doesNotContain("我和朋友周末在家", "secret-test-key");
        } finally {
            server.stop(0);
        }
    }

    private Candidate candidate(int id) {
        return new Candidate(
                id,
                "Game " + id,
                2025,
                1,
                new BigDecimal("8.5"),
                new BigDecimal("2.5"),
                2,
                4,
                45,
                60,
                List.of("Family"),
                List.of("Card Drafting"),
                List.of(),
                List.of(),
                List.of("Publisher"));
    }

    private static void respond(HttpExchange exchange, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
