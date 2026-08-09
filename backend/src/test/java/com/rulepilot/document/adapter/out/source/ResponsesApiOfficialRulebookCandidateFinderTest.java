package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ResponsesApiOfficialRulebookCandidateFinderTest {

    @Test
    void productionComponentHasAnUnambiguousSpringInjectionPoint() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ResponsesApiOfficialRulebookCandidateFinder.class);

            context.refresh();

            assertThat(context.getBean(ResponsesApiOfficialRulebookCandidateFinder.class).configured()).isFalse();
        }
    }

    @Test
    void acceptsOnlyRulebookSourcesObservedInWebSearchResults() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("candidates", List.of(
                    Map.of(
                            "title", "Official rules",
                            "url", "https://publisher.example/support/rules.pdf",
                            "publisher", "Publisher",
                            "language", "en",
                            "edition", "First",
                            "sourceIndexes", List.of(1, 2, 3)),
                    Map.of(
                            "title", "BGG file page",
                            "url", "https://boardgamegeek.com/filepage/123/rulebook",
                            "publisher", "",
                            "language", "en",
                            "edition", "First",
                            "sourceIndexes", List.of(2)),
                    Map.of(
                            "title", "Invented rules",
                            "url", "https://publisher.example/invented.pdf",
                            "publisher", "Publisher",
                            "language", "en",
                            "edition", "First",
                            "sourceIndexes", List.of(1)))));
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(
                    Map.of(
                            "type", "web_search_call",
                            "action", Map.of("sources", List.of(
                                    Map.of(
                                            "title", "Publisher support",
                                            "url", "https://publisher.example/support/rules.pdf"),
                                    Map.of(
                                            "title", "BGG Files",
                                            "url", "https://boardgamegeek.com/filepage/123/rulebook")))),
                    Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", content))))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var finder = new ResponsesApiOfficialRulebookCandidateFinder(
                    new OkHttpClient(),
                    new ObjectMapper(),
                    true,
                    "secret-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "search-model");

            var candidates = finder.find(new OfficialRulebookCandidateFinder.Request(
                    42, "Catalog Game", "First", 2024, "en",
                    List.of("Catalog Game", "目录游戏"), List.of("Publisher Studio"), List.of("rules.example")));

            assertThat(candidates).extracting(OfficialRulebookCandidateFinder.Candidate::url).containsExactly(
                    "https://publisher.example/support/rules.pdf",
                    "https://boardgamegeek.com/filepage/123/rulebook");
            assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.toString()).doesNotContain("secret-key"));
            assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            assertThat(requestBody.get()).contains(
                    "\"tools\":[{\"type\":\"web_search\"}]",
                    "\"max_output_tokens\":1100",
                    "\"reasoning\":{\"effort\":\"minimal\"}",
                    "Do not stop",
                    "BoardGameGeek Files",
                    "Catalog Game",
                    "目录游戏",
                    "Publisher Studio",
                    "rules.example",
                    "\\\"bggId\\\":42");
            assertThat(requestBody.get()).doesNotContain("secret-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachesValidatedDiscoverySoARepeatedSelectionDoesNotSearchAgain() throws Exception {
        AtomicReference<String> cached = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(ignored -> cached.get());
        when(values.increment(anyString())).thenReturn(1L);
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(java.time.Duration.class));

        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "title", "Official rules",
                    "url", "https://publisher.example/rules.pdf",
                    "publisher", "Publisher",
                    "language", "en",
                    "edition", "Base",
                    "sourceIndexes", List.of(1)))));
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(
                    Map.of(
                            "type", "web_search_call",
                            "action", Map.of("sources", List.of(Map.of(
                                    "url", "https://publisher.example/rules.pdf")))),
                    Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", content))))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var finder = new ResponsesApiOfficialRulebookCandidateFinder(
                    new OkHttpClient(),
                    new ObjectMapper(),
                    redis,
                    true,
                    "secret-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "search-model",
                    java.time.Duration.ofDays(30));
            var request = new OfficialRulebookCandidateFinder.Request(42, "Catalog Game", "Base", 2024, "en");

            assertThat(finder.find(request)).hasSize(1);
            assertThat(finder.find(request)).hasSize(1);

            assertThat(requests).hasValue(1);
            assertThat(cached).hasValueSatisfying(value -> assertThat(value).contains("rules.pdf"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTheLegacyQwenPlusFamilyBeforeAnyHttpCall() {
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);

        assertThatThrownBy(() -> new ResponsesApiOfficialRulebookCandidateFinder(
                        calls,
                        new ObjectMapper(),
                        true,
                        "secret-key",
                        "https://dashscope.aliyuncs.com/api/v1",
                        "qwen-plus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen-plus")
                .hasMessageContaining("prohibited");
        org.mockito.Mockito.verifyNoInteractions(calls);
    }

    @Test
    void negativelyCachesASuccessfulEmptySearchSoUiRetriesDoNotRepayTheProvider() throws Exception {
        AtomicReference<String> cached = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(ignored -> cached.get());
        when(values.increment(anyString())).thenReturn(1L);
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(java.time.Duration.class));

        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            ObjectMapper json = new ObjectMapper();
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(
                    Map.of("type", "web_search_call", "action", Map.of("sources", List.of())),
                    Map.of("type", "message", "content", List.of(Map.of(
                            "type", "output_text", "text", "{\"candidates\":[]}"))))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var finder = new ResponsesApiOfficialRulebookCandidateFinder(
                    new OkHttpClient(),
                    new ObjectMapper(),
                    redis,
                    true,
                    "secret-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen3.7-plus",
                    java.time.Duration.ofDays(30));
            var request = new OfficialRulebookCandidateFinder.Request(42, "Catalog Game", "Base", 2024, "en");

            assertThat(finder.find(request)).isEmpty();
            assertThat(finder.find(request)).isEmpty();

            assertThat(requests).hasValue(1);
            assertThat(cached).hasValue("[]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesAProviderCallAfterTheHourlyDiscoveryBudgetIsExhausted() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(2L);
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);
        var finder = new ResponsesApiOfficialRulebookCandidateFinder(
                calls,
                new ObjectMapper(),
                redis,
                true,
                "secret-key",
                "https://dashscope.aliyuncs.com/api/v1",
                "qwen3.7-plus",
                java.time.Duration.ofDays(30),
                java.time.Duration.ofMinutes(10),
                1,
                1,
                java.time.Clock.systemUTC());

        assertThat(finder.find(new OfficialRulebookCandidateFinder.Request(
                        42, "Catalog Game", "Base", 2024, "en")))
                .isEmpty();

        verify(calls, never()).newCall(any());
    }
}
