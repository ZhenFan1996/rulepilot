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
            String descriptiveTitle = "Official rules " + "complete edition details ".repeat(10);
            String content = json.writeValueAsString(Map.of(
                    "providerNote", "Search completed with independently observed sources.",
                    "candidates",
                    List.of(
                            Map.of(
                                    "title", descriptiveTitle,
                                    "url", "https://publisher.example/support/rules.pdf",
                                    "publisher", "Publisher",
                                    "language", "en",
                                    "edition", "First",
                                    "sourceIndexes", List.of(1, 2, 3, 1, 2, 3),
                                    "confidence", "high"),
                            Map.of(
                                    "title", "BGG file page",
                                    "url", "https://boardgamegeek.com/filepage/123/rulebook",
                                    "publisher", "",
                                    "language", "en",
                                    "edition", "First",
                                    "sourceIndexes", List.of(2)),
                            Map.of(
                                    "title", "Observed BGG download",
                                    "url", "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf",
                                    "publisher", "Community uploader",
                                    "language", "en",
                                    "edition", "First",
                                    "sourceIndexes", List.of(3)),
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
                                            "url", "https://boardgamegeek.com/filepage/123/rulebook"),
                                    Map.of(
                                            "title", "BGG direct download",
                                            "url", "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf")))),
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
                    "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf",
                    "https://boardgamegeek.com/filepage/123/rulebook");
            assertThat(candidates.getFirst().title()).startsWith("Official rules").hasSizeGreaterThan(180);
            assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.toString()).doesNotContain("secret-key"));
            assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            assertThat(requestBody.get()).contains(
                    "\"tools\":[{\"type\":\"web_search\"}]",
                    "\"max_output_tokens\":500",
                    "\"reasoning\":{\"effort\":\"minimal\"}",
                    "one bounded publisher-first search pass",
                    "filetype:pdf",
                    "Catalog Game",
                    "目录游戏",
                    "Publisher Studio",
                    "rules.example",
                    "\\\"bggId\\\":42");
            assertThat(requestBody.get()).doesNotContain(
                    "secret-key", "BoardGameGeek Files", "gstonegames.com", "1jour-1jeu.com");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void takesOneBoundedRecoveryPassFromObservedSourcePagesAndAcceptsAnObservedImageDocument() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "title", "官方规则书",
                    "url", "https://www.gstonegames.com/game/doc-1234.html",
                    "publisher", "集石",
                    "language", "zh-CN",
                    "edition", "Base",
                    "sourceIndexes", List.of(1)))));
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(
                    Map.of(
                            "type", "web_search_call",
                            "action", Map.of("sources", List.of(Map.of(
                                    "url", "https://www.gstonegames.com/game/doc-1234.html")))),
                    Map.of("type", "message", "content", List.of(Map.of(
                            "type", "output_text", "text", content))))));
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
            var request = new OfficialRulebookCandidateFinder.Request(
                    42, "Catalog Game", "Base", 2024, "zh-CN");

            var candidates = finder.findAfterSourcePages(request, List.of(
                    new OfficialRulebookCandidateFinder.Candidate(
                            "Publisher support",
                            "https://publisher.example/catalog-game/downloads",
                            "Publisher",
                            "zh-CN",
                            "Base")));

            assertThat(candidates)
                    .singleElement()
                    .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                    .isEqualTo("https://www.gstonegames.com/game/doc-1234.html");
            assertThat(requestBody.get()).contains(
                    "one final bounded recovery pass",
                    "https://publisher.example/catalog-game/downloads",
                    "gstonegames.com",
                    "ordered rulebook-page image viewer");
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
    void streamsQwenMaxSearchAndParsesTheCompletedResponseAfterToolProgress() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "title", "Official rules",
                    "url", "https://publisher.example/rules.pdf",
                    "publisher", "Publisher",
                    "language", "en",
                    "edition", "Base",
                    "sourceIndexes", List.of(1)))));
            Map<String, Object> completed = Map.of("output", List.of(
                    Map.of("type", "message", "content", List.of(Map.of(
                            "type", "output_text", "text", "I will search the official publisher."))),
                    Map.of(
                            "type", "web_search_call",
                            "action", Map.of("sources", List.of(Map.of(
                                    "url", "https://publisher.example/rules.pdf")))),
                    Map.of("type", "message", "content", List.of(Map.of(
                            "type", "output_text", "text", content)))));
            String response = "data:" + json.writeValueAsString(Map.of(
                            "type", "response.web_search_call.completed"))
                    + "\n\n"
                    + "data:" + json.writeValueAsString(Map.of(
                            "type", "response.completed", "response", completed))
                    + "\n\n";
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
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
                    "qwen3.7-max");

            assertThat(finder.find(new OfficialRulebookCandidateFinder.Request(
                            42, "Catalog Game", "Base", 2024, "en")))
                    .singleElement()
                    .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                    .isEqualTo("https://publisher.example/rules.pdf");
            assertThat(requestBody.get())
                    .contains(
                            "\"reasoning\":{\"effort\":\"minimal\"}",
                            "\"tools\":[{\"type\":\"web_search\"}]",
                            "\"stream\":true")
                    .doesNotContain("\"enable_thinking\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoversObservedTrustedPdfSourcesWhenAStreamEndsBeforeResponseCompleted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            ObjectMapper json = new ObjectMapper();
            Map<String, Object> item = Map.of(
                    "type", "web_search_call",
                    "action", Map.of("sources", List.of(Map.of(
                            "url", "https://publisher.example/Catalog-Game-Base-Rulebook.pdf"))));
            String response = "data:" + json.writeValueAsString(Map.of(
                            "type", "response.output_item.done", "item", item))
                    + "\n\n";
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
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
                    "qwen3.7-max");

            assertThat(finder.find(new OfficialRulebookCandidateFinder.Request(
                            42,
                            "Catalog Game",
                            "Base",
                            2024,
                            "en",
                            List.of("Catalog Game"),
                            List.of("Publisher Studio"),
                            List.of("publisher.example"))))
                    .singleElement()
                    .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                    .isEqualTo("https://publisher.example/Catalog-Game-Base-Rulebook.pdf");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoversOnlyTitleBoundRulebookPdfsFromObservedTrustedSourcesWhenModelJsonIsMalformed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            ObjectMapper json = new ObjectMapper();
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(
                    Map.of(
                            "type", "web_search_call",
                            "action", Map.of("sources", List.of(
                                    Map.of("url", "https://publisher.example/Catalog-Game-Landmarks-Rulebook.pdf"),
                                    Map.of("url", "https://publisher.example/Catalog-Game-rulebook.pdf"),
                                    Map.of("url", "https://publisher.example/Catalog-Game-Base-Game-Rulebook.pdf"),
                                    Map.of("url", "https://publisher.example/Catalog-Game-scoresheet.pdf"),
                                    Map.of("url", "https://publisher.example/Other-Game-rulebook.pdf"),
                                    Map.of("url", "https://untrusted.example/Catalog-Game-rulebook.pdf")))),
                    Map.of("type", "message", "content", List.of(Map.of(
                            "type", "output_text", "text", "candidate output was truncated: {"))))));
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
                    "qwen3.7-max");

            var candidates = finder.find(new OfficialRulebookCandidateFinder.Request(
                    42,
                    "Catalog Game",
                    "Base",
                    2024,
                    "en",
                    List.of("Catalog Game"),
                    List.of("Publisher Studio"),
                    List.of("publisher.example")));

            assertThat(candidates)
                    .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                    .containsExactly(
                            "https://publisher.example/Catalog-Game-Base-Game-Rulebook.pdf",
                            "https://publisher.example/Catalog-Game-rulebook.pdf",
                            "https://publisher.example/Catalog-Game-Landmarks-Rulebook.pdf");
            assertThat(candidates).allSatisfy(candidate ->
                    assertThat(candidate.publisher()).isEqualTo("Publisher Studio"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoversAnObservedTrustedRulebookWhenSearchCompletesWithoutAFinalModelMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            ObjectMapper json = new ObjectMapper();
            byte[] response = json.writeValueAsBytes(Map.of("output", List.of(Map.of(
                    "type", "web_search_call",
                    "action", Map.of("sources", List.of(Map.of(
                            "url", "https://publisher.example/Catalog-Game-Base-Rulebook.pdf")))))));
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
                    "qwen3.7-max");

            assertThat(finder.find(new OfficialRulebookCandidateFinder.Request(
                            42,
                            "Catalog Game",
                            "Base",
                            2024,
                            "en",
                            List.of("Catalog Game"),
                            List.of("Publisher Studio"),
                            List.of("publisher.example"))))
                    .singleElement()
                    .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                    .isEqualTo("https://publisher.example/Catalog-Game-Base-Rulebook.pdf");
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
    void rejectsQwen37PlusBecauseResponsesWebSearchRequiresADedicatedModel() {
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);

        assertThatThrownBy(() -> new ResponsesApiOfficialRulebookCandidateFinder(
                        calls,
                        new ObjectMapper(),
                        true,
                        "secret-key",
                        "https://dashscope.aliyuncs.com/api/v1",
                        "qwen3.7-plus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen3.7-plus")
                .hasMessageContaining("Responses web-search");
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
                    "qwen3.7-max",
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
                "qwen3.7-max",
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
