package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.adapter.out.model.SpringAiBoardGameRecommendationModel;
import com.rulepilot.recommendation.adapter.out.research.ResponsesApiBoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Opt-in end-to-end evidence for the first public-search turn; artifacts stay under ignored .local/. */
@Tag("paid-recommendation-public-search-canary")
class BoardGameRecommendationPublicSearchPaidCanaryTest {

    private static final Duration RECOMMENDATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration WEB_SEARCH_TIMEOUT = Duration.ofSeconds(25);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    @SuppressWarnings("unchecked")
    void answersOnePublicFactWithinBudgetWithoutRepeatingTypedReads() throws Exception {
        assumeTrue("true".equalsIgnoreCase(
                System.getenv("RULEPILOT_RECOMMENDATION_PUBLIC_SEARCH_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        String apiKey = environment(prefix + "_API_KEY", null);
        String baseUrl = environment(prefix + "_BASE_URL", null);
        String outerModelName = System.getenv("BGG_RECOMMENDATION_MODEL");
        if (outerModelName == null || outerModelName.isBlank()) {
            outerModelName = environment(prefix + "_MODEL", null);
        } else {
            outerModelName = outerModelName.strip();
        }
        String webSearchApiKey = environment("WEB_SEARCH_API_KEY", apiKey);
        String webSearchBaseUrl = environment("WEB_SEARCH_BASE_URL", baseUrl);
        String webSearchModel = environment("WEB_SEARCH_MODEL", "qwen3.8-flash");
        OuterCapture outer = new OuterCapture(provider, outerModelName);
        BoardGameRecommendationModel model =
                model(provider, apiKey, baseUrl, outerModelName, outer);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(1L);
        BoardGameRecommendationWebResearch liveResearch =
                new ResponsesApiBoardGameRecommendationWebResearch(
                        json,
                        redis,
                        true,
                        webSearchApiKey,
                        webSearchBaseUrl,
                        webSearchModel,
                        WEB_SEARCH_TIMEOUT,
                        Duration.ofDays(7),
                        20,
                        2);
        WebSearchCapture inner = new WebSearchCapture(liveResearch, webSearchModel);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), RECOMMENDATION_TIMEOUT);
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new NoCatalog(), inner),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        long started = System.nanoTime();
        BoardGameRecommendationAgent.ConversationResponse response = null;
        String failure = "";

        try {
            response = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "只查公开资料回答：Gen Con 目前由哪个组织运营？附上可核验来源；不要推荐游戏。"),
                    "zh-CN");
            long totalMs = elapsed(started);

            assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(response.assistantMessage()).isNotBlank();
            assertThat(response.harness().modelCalls()).isPositive();
            assertThat(response.harness().actions()).contains("DISCOVER_CANDIDATES");
            assertThat(response.researchSources())
                    .isNotEmpty()
                    .allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
            assertThat(inner.sourceCount()).isPositive();
            assertThat(outer.hasRepeatedTypedRead()).isFalse();
            assertThat(totalMs).isLessThan(RECOMMENDATION_TIMEOUT.toMillis());
            writeArtifact(outer, inner, response, totalMs, "");
        } catch (Throwable thrown) {
            failure = thrown.getClass().getSimpleName();
            writeArtifact(outer, inner, response, elapsed(started), failure);
            throw thrown;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private BoardGameRecommendationModel model(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            OuterCapture capture) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, RECOMMENDATION_TIMEOUT)
                .create(provider, apiKey, baseUrl, modelName);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        var resolvedModel = new RuntimeModelConfiguration.ResolvedModel(
                chatModel, provider, modelName, "deepseek".equals(provider));
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(resolvedModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(modelName);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider));
        BoardGameRecommendationModel delegate =
                new SpringAiBoardGameRecommendationModel(configuration, 0.0);
        return new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(BoardGameRecommendationModel.Request request) {
                long started = System.nanoTime();
                try {
                    Turn result = delegate.next(request);
                    capture.complete(request, result, elapsed(started));
                    return result;
                } catch (RuntimeException | Error thrown) {
                    capture.fail(request, thrown, elapsed(started));
                    throw thrown;
                }
            }

            @Override
            public Turn stream(
                    BoardGameRecommendationModel.Request request,
                    String ownerUsername,
                    Consumer<String> accumulatedTextListener) {
                long started = System.nanoTime();
                try {
                    Turn result = delegate.stream(request, ownerUsername, accumulatedTextListener);
                    capture.complete(request, result, elapsed(started));
                    return result;
                } catch (RuntimeException | Error thrown) {
                    capture.fail(request, thrown, elapsed(started));
                    throw thrown;
                }
            }
        };
    }

    private void writeArtifact(
            OuterCapture outer,
            WebSearchCapture inner,
            BoardGameRecommendationAgent.ConversationResponse response,
            long totalMs,
            String failure)
            throws Exception {
        String label = environment("RULEPILOT_RECOMMENDATION_CANARY_LABEL", "current")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path output = Path.of(System.getProperty("user.dir"))
                .getParent()
                .resolve(".local/agent-evaluation/recommendation-public-search-paid-canary-" + label + ".json");
        Files.createDirectories(output.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("generatedAt", Instant.now().toString());
        report.put("outerProvider", outer.provider);
        report.put("outerModel", outer.model);
        report.put("outerModelCalls", outer.callCount());
        report.put("outerCalls", outer.calls);
        report.put("innerWebSearch", Map.of(
                "model", inner.model,
                "callCount", inner.callCount(),
                "calls", inner.calls(),
                "elapsedMs", inner.elapsedMs(),
                "sourceCount", inner.sourceCount()));
        report.put("totalProviderCalls", outer.callCount() + inner.callCount());
        report.put("repeatedTypedRead", outer.hasRepeatedTypedRead());
        report.put("totalElapsedMs", totalMs);
        report.put("failure", failure);
        if (response != null) {
            report.put("published", Map.of(
                    "outcome", response.outcome().name(),
                    "assistantMessage", response.assistantMessage(),
                    "sourceCount", response.researchSources().size(),
                    "sources", response.researchSources(),
                    "harness", response.harness()));
        }
        Files.writeString(
                output,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            assumeTrue(defaultValue != null, name + " is required for the paid canary");
            return defaultValue;
        }
        return value.strip();
    }

    private static long elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static final class OuterCapture {
        private static final Set<String> READ_TOOLS = Set.of(
                BoardGameRecommendationAgent.RESOLVE_TOOL,
                BoardGameRecommendationAgent.BROWSE_TOOL,
                BoardGameRecommendationAgent.DISCOVER_TOOL,
                BoardGameRecommendationAgent.LOOKUP_TOOL,
                BoardGameRecommendationAgent.RESEARCH_TOOL);

        private final String provider;
        private final String model;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final Set<String> typedReadFingerprints = new LinkedHashSet<>();
        private boolean repeatedTypedRead;

        private OuterCapture(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        private synchronized void complete(
                BoardGameRecommendationModel.Request request,
                Turn turn,
                long elapsedMs) {
            turn.toolCalls().stream()
                    .filter(call -> READ_TOOLS.contains(call.name()))
                    .map(call -> call.name() + "\n" + call.argumentsJson())
                    .forEach(fingerprint -> {
                        if (!typedReadFingerprints.add(fingerprint)) repeatedTypedRead = true;
                    });
            calls.add(Map.of(
                    "status", "COMPLETED",
                    "elapsedMs", elapsedMs,
                    "toolNames", request.tools().stream()
                            .map(BoardGameRecommendationModel.ToolSpec::name)
                            .toList(),
                    "toolCalls", turn.toolCalls().stream()
                            .map(call -> Map.of("name", call.name(), "argumentsJson", call.argumentsJson()))
                            .toList(),
                    "assistantText", turn.text() == null ? "" : turn.text()));
        }

        private synchronized void fail(
                BoardGameRecommendationModel.Request request,
                Throwable thrown,
                long elapsedMs) {
            calls.add(Map.of(
                    "status", "FAILED",
                    "elapsedMs", elapsedMs,
                    "toolNames", request.tools().stream()
                            .map(BoardGameRecommendationModel.ToolSpec::name)
                            .toList(),
                    "failureType", thrown.getClass().getSimpleName()));
        }

        private synchronized int callCount() {
            return calls.size();
        }

        private synchronized boolean hasRepeatedTypedRead() {
            return repeatedTypedRead;
        }
    }

    private static final class WebSearchCapture implements BoardGameRecommendationWebResearch {
        private final BoardGameRecommendationWebResearch delegate;
        private final String model;
        private final List<InnerCall> calls = new ArrayList<>();

        private WebSearchCapture(BoardGameRecommendationWebResearch delegate, String model) {
            this.delegate = delegate;
            this.model = model;
        }

        @Override
        public boolean configured() {
            return delegate.configured();
        }

        @Override
        public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
            long started = System.nanoTime();
            try {
                Optional<CandidateDiscovery> result = delegate.discover(request);
                record(
                        "DISCOVER",
                        result.isPresent() ? "PRESENT" : "EMPTY",
                        elapsed(started),
                        result.map(value -> value.sources().size()).orElse(0));
                return result;
            } catch (RuntimeException | Error thrown) {
                record("DISCOVER", "FAILED:" + thrown.getClass().getSimpleName(), elapsed(started), 0);
                throw thrown;
            }
        }

        @Override
        public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
            long started = System.nanoTime();
            try {
                Optional<Research> result = delegate.research(request);
                record(
                        "RESEARCH",
                        result.isPresent() ? "PRESENT" : "EMPTY",
                        elapsed(started),
                        result.map(value -> value.sources().size()).orElse(0));
                return result;
            } catch (RuntimeException | Error thrown) {
                record("RESEARCH", "FAILED:" + thrown.getClass().getSimpleName(), elapsed(started), 0);
                throw thrown;
            }
        }

        private synchronized void record(
                String operation,
                String status,
                long elapsedMs,
                int sourceCount) {
            calls.add(new InnerCall(operation, status, elapsedMs, sourceCount));
        }

        private synchronized int callCount() {
            return calls.size();
        }

        private synchronized long elapsedMs() {
            return calls.stream().mapToLong(InnerCall::elapsedMs).sum();
        }

        private synchronized int sourceCount() {
            return calls.stream().mapToInt(InnerCall::sourceCount).sum();
        }

        private synchronized List<InnerCall> calls() {
            return List.copyOf(calls);
        }
    }

    private record InnerCall(
            String operation,
            String status,
            long elapsedMs,
            int sourceCount) {}

    private static final class NoCatalog implements BoardGameRecommendationCatalog {
        @Override
        public CandidateSet findCandidates(
                BggGameType requiredType,
                List<BggGameType> suggestedTypes,
                int maximum) {
            return new CandidateSet(0, List.of());
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            return List.of();
        }

        @Override
        public int gameCount() {
            return 0;
        }
    }
}
