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
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Request;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
            respond(exchange, functionResponse(
                    """
                    [
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
                    ]
                    """,
                    "record_game_fit_research",
                    """
                    {"games":[{"bggId":10,"observations":[{"text":"The publisher describes a short guided teach.","sourceIndexes":[1,15]}]}]}
                    """));
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
            assertThat(sent.path("tools")).hasSize(2);
            assertThat(sent.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
            JsonNode functionTool = sent.path("tools").get(1);
            assertThat(functionTool.path("type").asText()).isEqualTo("function");
            assertThat(functionTool.path("name").asText()).isEqualTo("record_game_fit_research");
            assertThat(functionTool.path("parameters").path("additionalProperties").asBoolean()).isFalse();
            assertThat(functionTool.path("parameters").path("properties").path("games").path("maxItems").asInt())
                    .isEqualTo(5);
            JsonNode observationSchema = functionTool.path("parameters")
                    .path("properties")
                    .path("games")
                    .path("items")
                    .path("properties")
                    .path("observations");
            assertThat(observationSchema.path("maxItems").asInt()).isEqualTo(2);
            assertThat(observationSchema
                            .path("items")
                            .path("properties")
                            .path("sourceIndexes")
                            .path("maxItems")
                            .asInt())
                    .isEqualTo(2);
            assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
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
    void deduplicatesEquivalentHttpsSourcesAndTheirRemappedReferences() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, functionResponse(
                """
                [
                  {"title":"Publisher guide","url":"https://publisher.example/games/10"},
                  {"title":"Same publisher guide","url":"https://publisher.example/games/10"}
                ]
                """,
                "record_game_fit_research",
                """
                {"games":[{"bggId":10,"observations":[{"text":"The same guide supports this observation.","sourceIndexes":[1,2]}]}]}
                """)));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var research = adapter.research(new Request(List.of(candidate(10)), "en")).orElseThrow();

            assertThat(research.sources()).singleElement().satisfies(source -> {
                assertThat(source.index()).isEqualTo(1);
                assertThat(source.url()).isEqualTo("https://publisher.example/games/10");
            });
            assertThat(research.games().getFirst().observations().getFirst().sourceIndexes())
                    .containsExactly(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsSourceIndexesOutsideThisResponsesSearchResultsWithoutCachingThem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, functionResponse(
                """
                [{"title":"Publisher guide","url":"https://publisher.example/games/10"}]
                """,
                "record_game_fit_research",
                """
                {"games":[{"bggId":10,"observations":[{"text":"This citation is not owned by the response.","sourceIndexes":[4294967297]}]}]}
                """)));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            assertThat(adapter.research(new Request(List.of(candidate(10)), "en"))).isEmpty();
            org.mockito.Mockito.verify(values, org.mockito.Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dropsInvalidFitResearchItemsWhileKeepingValidSiblings() throws Exception {
        ResearchRun run = runResearch(
                """
                [
                  {"title":"Publisher guide","url":"https://publisher.example/games/10"},
                  {"title":"Unsafe discussion","url":"http://unsafe.example/games/10"},
                  {"title":"Independent review","url":"https://reviews.example/games/20"}
                ]
                """,
                """
                {"games":[
                  {"bggId":10,"observations":[
                    {"text":"The publisher documents a guided opening round.","sourceIndexes":[1]},
                    {"text":"This observation cites a source outside the HTTPS boundary.","sourceIndexes":[2]}
                  ]},
                  {"bggId":999,"observations":[
                    {"text":"This game was not part of the requested candidate set.","sourceIndexes":[1]}
                  ]},
                  {"bggId":20,"observations":[
                    {"text":"The review reports a brisk two-player pace.","sourceIndexes":[3]}
                  ]}
                ]}
                """,
                List.of(candidate(10), candidate(20)));

        assertThat(run.research()).hasValueSatisfying(research -> {
            assertThat(research.games()).extracting(game -> game.bggId()).containsExactly(10, 20);
            assertThat(research.games()).allSatisfy(game -> assertThat(game.observations()).hasSize(1));
            assertThat(research.games().get(0).observations().getFirst().sourceIndexes()).containsExactly(1);
            assertThat(research.games().get(1).observations().getFirst().sourceIndexes()).containsExactly(2);
            assertThat(research.sources()).extracting(source -> source.domain())
                    .containsExactly("publisher.example", "reviews.example");
            assertThat(research.sources()).allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
        });
        org.mockito.Mockito.verify(run.cache())
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void returnsEmptyWhenEveryFitResearchItemIsInvalid() throws Exception {
        ResearchRun run = runResearch(
                """
                [
                  {"title":"Publisher guide","url":"https://publisher.example/games/10"},
                  {"title":"Unsafe discussion","url":"http://unsafe.example/games/10"}
                ]
                """,
                """
                {"games":[
                  {"bggId":10,"observations":[
                    {"text":"This observation cites a source outside the HTTPS boundary.","sourceIndexes":[2]},
                    {"sourceIndexes":[1]}
                  ]},
                  {"bggId":999,"observations":[
                    {"text":"This game was not part of the requested candidate set.","sourceIndexes":[1]}
                  ]}
                ]}
                """,
                List.of(candidate(10), candidate(20)));

        assertThat(run.research()).isEmpty();
        org.mockito.Mockito.verify(run.cache(), org.mockito.Mockito.never())
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void rejectsBggIdsOutsideTheRequestedPositiveIntegerBoundary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, functionResponse(
                """
                [{"title":"Publisher guide","url":"https://publisher.example/games/10"}]
                """,
                "record_game_fit_research",
                """
                {"games":[{"bggId":4294967306,"observations":[{"text":"An oversized id must not truncate to ten.","sourceIndexes":[1]}]}]}
                """)));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            assertThat(adapter.research(new Request(List.of(candidate(10)), "en"))).isEmpty();
            org.mockito.Mockito.verify(values, org.mockito.Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
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
                        {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\\"candidates\\\":[{\\\"name\\\":\\\"Example Game\\\",\\\"fitObservation\\\":\\\"The search result describes the requested table experience.\\\",\\\"sourceIndexes\\\":[1]}],\\\"publicContext\\\":[]}"}
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
            assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
            assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("none");
            assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(1_200);
            assertThat(sent.path("tools")).hasSize(2);
            assertThat(sent.path("tools").get(1).path("name").asText())
                    .isEqualTo("record_candidate_discovery");
            JsonNode discoverySchema = sent.path("tools").get(1).path("parameters").path("properties");
            assertThat(discoverySchema.has("relationship")).isFalse();
            assertThat(discoverySchema.path("candidates").path("maxItems").asInt()).isEqualTo(6);
            assertThat(discoverySchema.path("publicContext").path("maxItems").asInt()).isEqualTo(4);
            assertThat(sent.path("input").asText())
                    .contains(
                            "Search the web once",
                            "Keep subject verbatim",
                            "input locale",
                            "returned sources, not memory",
                            "record_candidate_discovery",
                            "atomic sourced subject-relation-object",
                            "Do not invent BGG IDs",
                            "requested locale",
                            "Science Fiction",
                            "THEMATIC")
                    .doesNotContain("\"bggId\"");
            assertThat(body.get()).doesNotContain("科幻主题", "secret-test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsTypedPublicContextForAnEventWithoutInventingABggCarrier() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"Convention organizer","url":"https://events.example/convention"}
                    ]}},
                    {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\"candidates\\":[],\\"publicContext\\":[{\\"subjectKind\\":\\"EVENT\\",\\"subject\\":\\"North Harbor Games Week\\",\\"relation\\":\\"organized by\\",\\"object\\":\\"Harbor Tabletop Association\\",\\"statement\\":\\"North Harbor Games Week is organized by the Harbor Tabletop Association.\\",\\"sourceIndexes\\":[1]}]}"}
                  ]
                }
                """));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var result = adapter.discover(new DiscoveryRequest(
                    "Who organizes North Harbor Games Week?",
                    "North Harbor Games Week",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY));

            assertThat(result).hasValueSatisfying(discovery -> {
                assertThat(discovery.candidates()).isEmpty();
                assertThat(discovery.publicContext()).singleElement().satisfies(evidence -> {
                    assertThat(evidence.id()).isEqualTo("P1");
                    assertThat(evidence.subjectKind().name()).isEqualTo("EVENT");
                    assertThat(evidence.subject()).isEqualTo("North Harbor Games Week");
                    assertThat(evidence.relation()).isEqualTo("organized by");
                    assertThat(evidence.object()).isEqualTo("Harbor Tabletop Association");
                    assertThat(evidence.sourceIndexes()).containsExactly(1);
                });
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsValidPublicContextWhenACandidateEnrichmentIsMalformed() throws Exception {
        DiscoveryRun run = runDiscovery(
                """
                [{"title":"Public organization record","url":"https://records.example/forum"}]
                """,
                """
                {
                  "candidates":[{"name":"Not publishable without the required observation","sourceIndexes":[1]}],
                  "publicContext":[{
                    "subjectKind":"ORGANIZATION",
                    "subject":"Harbor Tabletop Association",
                    "relation":"operates",
                    "object":"North Harbor Games Week",
                    "statement":"Harbor Tabletop Association operates North Harbor Games Week.",
                    "sourceIndexes":[1]
                  }]
                }
                """);

        assertThat(run.discovery()).hasValueSatisfying(discovery -> {
            assertThat(discovery.candidates()).isEmpty();
            assertThat(discovery.publicContext()).singleElement().satisfies(evidence -> {
                assertThat(evidence.id()).isEqualTo("P1");
                assertThat(evidence.object()).isEqualTo("North Harbor Games Week");
                assertThat(evidence.sourceIndexes()).containsExactly(1);
            });
            assertThat(discovery.sources()).singleElement().satisfies(source ->
                    assertThat(source.url()).isEqualTo("https://records.example/forum"));
        });
    }

    @Test
    void dropsAnInvalidPublicContextItemWhileKeepingItsValidSibling() throws Exception {
        DiscoveryRun run = runDiscovery(
                """
                [
                  {"title":"Public organization record","url":"https://records.example/forum"},
                  {"title":"Unsafe source","url":"http://unsafe.example/forum"}
                ]
                """,
                """
                {
                  "candidates":[],
                  "publicContext":[
                    {
                      "subjectKind":"ORGANIZATION",
                      "subject":"Unattributed operator",
                      "relation":"operates",
                      "object":"North Harbor Games Week",
                      "statement":"This item cites a source that failed the HTTPS boundary.",
                      "sourceIndexes":[2]
                    },
                    {
                      "subjectKind":"EVENT",
                      "subject":"North Harbor Games Week",
                      "relation":"operated by",
                      "object":"Harbor Tabletop Association",
                      "statement":"North Harbor Games Week is operated by Harbor Tabletop Association.",
                      "sourceIndexes":[1]
                    }
                  ]
                }
                """);

        assertThat(run.discovery()).hasValueSatisfying(discovery -> {
            assertThat(discovery.publicContext()).singleElement().satisfies(evidence -> {
                assertThat(evidence.id()).isEqualTo("P1");
                assertThat(evidence.subject()).isEqualTo("North Harbor Games Week");
                assertThat(evidence.sourceIndexes()).containsExactly(1);
            });
            assertThat(discovery.sources())
                    .singleElement()
                    .satisfies(source -> assertThat(source.url()).startsWith("https://"));
        });
    }

    @Test
    void returnsEmptyWhenEveryDiscoveryEnrichmentIsInvalid() throws Exception {
        DiscoveryRun run = runDiscovery(
                """
                [{"title":"Unrelated public result","url":"https://records.example/unrelated"}]
                """,
                """
                {
                  "candidates":[{
                    "name":"Unowned candidate",
                    "fitObservation":"This candidate points outside the response-owned source set.",
                    "sourceIndexes":[2]
                  }],
                  "publicContext":[{
                    "subjectKind":"EVENT",
                    "subject":"Unowned event",
                    "relation":"operated by",
                    "object":"Unknown organization",
                    "statement":"This fact also points outside the response-owned source set.",
                    "sourceIndexes":[2]
                  }]
                }
                """);

        assertThat(run.discovery()).isEmpty();
        org.mockito.Mockito.verify(run.cache(), org.mockito.Mockito.never())
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void rejectsProseAndFencedJsonInsteadOfTreatingItAsMachineState() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            providerCalls.incrementAndGet();
            respond(exchange, """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"Tabletop designer profile","url":"https://tabletop.example/designers/profile"}
                    ]}},
                    {"type":"message","content":[{"type":"output_text","text":"The search supports one public fact.\\n\\n```json\\n{\\\"candidates\\\":[],\\\"publicContext\\\":[{\\\"subjectKind\\\":\\\"PERSON\\\",\\\"subject\\\":\\\"Ada Vale\\\",\\\"relation\\\":\\\"designs\\\",\\\"object\\\":\\\"board games\\\",\\\"statement\\\":\\\"Ada Vale designs board games.\\\",\\\"sourceIndexes\\\":[1]}]}\\n```"}]}
                  ]
                }
                """);
        });
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            DiscoveryRequest request = new DiscoveryRequest(
                    "games by a creator known by a community alias",
                    "the community alias",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY);
            assertThat(adapter.discover(request)).isEmpty();
            assertThat(providerCalls).hasValue(1);
            org.mockito.Mockito.verify(values, org.mockito.Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTheRemovedRelationshipEnvelopeEvenWhenPublicContextIsValid() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"Tabletop designer profile","url":"https://tabletop.example/designers/profile"}
                    ]}},
                    {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\"relationship\\":{\\"kind\\":\\"DESIGNER\\",\\"entityNames\\":[\\"Ada Vale\\"],\\"sourceIndexes\\":[1]},\\"candidates\\":[],\\"publicContext\\":[{\\"subjectKind\\":\\"PERSON\\",\\"subject\\":\\"Ada Vale\\",\\"relation\\":\\"designs\\",\\"object\\":\\"board games\\",\\"statement\\":\\"Ada Vale designs board games.\\",\\"sourceIndexes\\":[1]}]}"}
                  ]
                }
                """));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            DiscoveryRequest request = new DiscoveryRequest(
                    "games by a creator known by a community alias",
                    "the community alias",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY);

            assertThat(adapter.discover(request)).isEmpty();
            org.mockito.Mockito.verify(values, org.mockito.Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scopesDiscoveryCacheToTheFullQueryEvenWhenTheSubjectIsTheSame() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(
                exchange,
                providerCalls.incrementAndGet() == 1
                        ? """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"One event appearance","url":"https://events.example/one-appearance"}
                    ]}},
                    {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\"candidates\\":[],\\"publicContext\\":[{\\"subjectKind\\":\\"PERSON\\",\\"subject\\":\\"Ada Vale\\",\\"relation\\":\\"appeared at\\",\\"object\\":\\"One Event\\",\\"statement\\":\\"Ada Vale appeared at One Event.\\",\\"sourceIndexes\\":[1]}]}"}
                  ]
                }
                """
                        : """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"Collaboration announcement","url":"https://studio.example/collaboration"}
                    ]}},
                    {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\"candidates\\":[],\\"publicContext\\":[{\\"subjectKind\\":\\"PERSON\\",\\"subject\\":\\"Ada Vale\\",\\"relation\\":\\"collaborates with\\",\\"object\\":\\"North Studio\\",\\"statement\\":\\"Ada Vale collaborates with North Studio.\\",\\"sourceIndexes\\":[1]}]}"}
                  ]
                }
                """));
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            Map<String, String> stored = new java.util.concurrent.ConcurrentHashMap<>();
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
            when(values.increment(anyString())).thenReturn(1L);
            org.mockito.Mockito.doAnswer(invocation -> {
                        stored.put(invocation.getArgument(0), invocation.getArgument(1));
                        return null;
                    })
                    .when(values)
                    .set(anyString(), anyString(), any(Duration.class));
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), json, redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            DiscoveryRequest appearanceRequest = new DiscoveryRequest(
                    "Where did Ada Vale appear?",
                    "Ada Vale",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY);
            DiscoveryRequest collaborationRequest = new DiscoveryRequest(
                    "Who does Ada Vale collaborate with?",
                    appearanceRequest.subject(),
                    appearanceRequest.candidateTypes(),
                    appearanceRequest.locale(),
                    appearanceRequest.goal());

            var appearance = adapter.discover(appearanceRequest).orElseThrow();
            var collaboration = adapter.discover(collaborationRequest).orElseThrow();
            var cachedAppearance = adapter.discover(appearanceRequest).orElseThrow();

            assertThat(appearance.publicContext().getFirst().relation()).isEqualTo("appeared at");
            assertThat(collaboration.publicContext().getFirst().relation()).isEqualTo("collaborates with");
            assertThat(cachedAppearance).isEqualTo(appearance);
            assertThat(providerCalls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsAnEmptyIdentityAsNoEvidenceInsteadOfPublishingAnEntity() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, """
                {
                  "output": [
                    {"type":"web_search_call","action":{"sources":[
                      {"title":"Unrelated search result","url":"https://tabletop.example/unrelated"}
                    ]}},
                    {"type":"function_call","name":"record_candidate_discovery","arguments":"{\\\"candidates\\\":[],\\\"publicContext\\\":[]}"}
                  ]
                }
                """));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            var result = adapter.discover(new DiscoveryRequest(
                    "Do you know this unsupported community nickname?",
                    "unsupported community nickname",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY));

            assertThat(result).isEmpty();
            org.mockito.Mockito.verify(values, org.mockito.Mockito.never())
                    .set(anyString(), anyString(), any(Duration.class));
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
            respond(exchange, functionResponse(
                    """
                    [{"title":"Publisher rules","url":"https://publisher.example/rules"}]
                    """,
                    "record_game_fit_research",
                    """
                    {"games":[{"bggId":10,"observations":[{"text":"Each round alternates agent turns before a reveal turn.","sourceIndexes":[1]}]}]}
                    """));
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

    @SuppressWarnings("unchecked")
    private ResearchRun runResearch(
            String sourcesJson,
            String argumentsJson,
            List<Candidate> candidates) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(
                exchange,
                functionResponse(sourcesJson, "record_game_fit_research", argumentsJson)));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            Optional<Research> result = adapter.research(new Request(candidates, "en"));
            return new ResearchRun(result, values);
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private DiscoveryRun runDiscovery(String sourcesJson, String argumentsJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> respond(
                exchange,
                functionResponse(sourcesJson, "record_candidate_discovery", argumentsJson)));
        server.start();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(null);
            when(values.increment(anyString())).thenReturn(1L);
            var adapter = new ResponsesApiBoardGameRecommendationWebResearch(
                    new OkHttpClient(), new ObjectMapper(), redis, true, "secret-test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "research-model", Duration.ofDays(7), 20, 2,
                    Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));

            Optional<CandidateDiscovery> result = adapter.discover(new DiscoveryRequest(
                    "Who operates this public event?",
                    "the public event",
                    List.of(),
                    "en",
                    com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal.IDENTITY_ONLY));
            return new DiscoveryRun(result, values);
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

    private static String functionResponse(String sourcesJson, String functionName, String argumentsJson)
            throws IOException {
        ObjectMapper json = new ObjectMapper();
        var root = json.createObjectNode();
        var output = root.putArray("output");
        var search = output.addObject();
        search.put("type", "web_search_call");
        search.putObject("action").set("sources", json.readTree(sourcesJson));
        var function = output.addObject();
        function.put("type", "function_call");
        function.put("name", functionName);
        function.put("arguments", argumentsJson.strip());
        return json.writeValueAsString(root);
    }

    private static void respond(HttpExchange exchange, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record DiscoveryRun(
            Optional<CandidateDiscovery> discovery,
            ValueOperations<String, String> cache) {}

    private record ResearchRun(
            Optional<Research> research,
            ValueOperations<String, String> cache) {}
}
