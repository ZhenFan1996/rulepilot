package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Request;
import com.rulepilot.recommendation.adapter.out.model.SpringAiBoardGameRecommendationModel;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * One opt-in real-model canary for the ordinary direct recommendation path. Raw provider and
 * tool payloads stay under the ignored {@code .local/} directory.
 */
@Tag("paid-recommendation-canary")
class BoardGameRecommendationAgentPaidCanaryTest {

    private static final Duration RECOMMENDATION_TIMEOUT = Duration.ofSeconds(45);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void publishesAComplexTitleBoundedSlateWithoutOptionalResearch() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), RECOMMENDATION_TIMEOUT);
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), configuredResearchThatMustNotRun()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        long started = System.nanoTime();

        try {
            var response = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "请只推荐两款正式英文标题中包含 Harbor 的独立桌游。比较核心机制、四人体验、教学难度与局势变化，把最适合两位新手和两位熟手同桌、90 分钟内完成的一款放第一；不要推荐扩展。"),
                    "zh-CN");
            long totalMs = elapsed(started);

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.games()).hasSize(2).allSatisfy(game -> {
                assertThat(game.game().ranking().sourceName()).containsIgnoringCase("Harbor");
                assertThat(game.replyParts()).isNotEmpty();
            });
            assertThat(response.assistantMessage()).isNotBlank();
            assertThat(response.harness().fallbackUsed()).isFalse();
            assertThat(response.harness().modelCalls()).isEqualTo(2);
            assertThat(response.harness().catalogCalls()).isEqualTo(1);
            assertThat(response.harness().webResearchCalls()).isZero();
            assertThat(response.harness().actions())
                    .noneMatch(action -> action.startsWith("REJECTED_")
                            || action.startsWith("FALLBACK_")
                            || action.equals("RUN_DEADLINE_EXCEEDED")
                            || action.equals("RECOMMENDATION_NARRATIVE_UNAVAILABLE"));
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.BROWSE_TOOL)).hasSize(1);
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RESEARCH_TOOL)).isEmpty();
            assertThat(capture.structuredCallCount()).isOne();
            assertThat(totalMs).isLessThan(20_000L);

            ToolCall browse = capture.lastCandidateProducingToolCall();
            JsonNode arguments = json.readTree(browse.argumentsJson());
            assertThat(arguments.path("titleConstraint").path("operator").asText()).isEqualTo("CONTAINS");
            assertThat(arguments.path("titleConstraint").path("value").asText())
                    .containsIgnoringCase("Harbor");
            assertThat(arguments.path("evidence").asText()).isEqualTo("U1");
            assertThat(arguments.has("candidateUse")).isFalse();
            writeArtifact(capture, response, totalMs, null);
        } catch (Throwable failure) {
            writeArtifact(capture, null, elapsed(started), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private BoardGameRecommendationModel model(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            Capture capture) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, RECOMMENDATION_TIMEOUT)
                .create(provider, apiKey, baseUrl, modelName);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(modelName);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider));
        double temperature = Double.parseDouble(
                environment("RULEPILOT_RECOMMENDATION_CANARY_TEMPERATURE", "0.0"));
        BoardGameRecommendationModel delegate =
                new SpringAiBoardGameRecommendationModel(configuration, temperature);
        return new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public boolean structuredPublicationConfigured(String ownerUsername) {
                return true;
            }

            @Override
            public Turn next(BoardGameRecommendationModel.Request request) {
                long started = System.nanoTime();
                int callIndex = capture.begin("react", request);
                try {
                    Turn result = delegate.next(request);
                    capture.complete(callIndex, result, elapsed(started));
                    return result;
                } catch (RuntimeException | Error failure) {
                    capture.fail(callIndex, failure, elapsed(started));
                    throw failure;
                }
            }

            @Override
            public StructuredTurn streamStructured(
                    BoardGameRecommendationModel.Request request,
                    String ownerUsername,
                    java.util.function.Consumer<String> jsonDeltaListener) {
                long started = System.nanoTime();
                int callIndex = capture.begin("structured_publication", request);
                try {
                    StructuredTurn result = delegate.streamStructured(request, ownerUsername, jsonDeltaListener);
                    capture.completeStructured(callIndex, result, elapsed(started));
                    return result;
                } catch (RuntimeException | Error failure) {
                    capture.fail(callIndex, failure, elapsed(started));
                    throw failure;
                }
            }
        };
    }

    private BoardGameRecommendationWebResearch configuredResearchThatMustNotRun() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("a useful selectable slate must publish before optional research");
            }
        };
    }

    private void writeArtifact(
            Capture capture,
            BoardGameRecommendationAgent.ConversationResponse response,
            long latencyMs,
            String failure) throws Exception {
        String label = environment("RULEPILOT_RECOMMENDATION_CANARY_LABEL", "current")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path output = Path.of(System.getProperty("user.dir"))
                .getParent()
                .resolve(".local/agent-evaluation/recommendation-paid-canary-" + label + ".json");
        Files.createDirectories(output.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("provider", capture.provider);
        report.put("model", capture.model);
        report.put("temperature", Double.parseDouble(
                environment("RULEPILOT_RECOMMENDATION_CANARY_TEMPERATURE", "0.0")));
        report.put("rawModelCalls", capture.calls);
        report.put("latencyMs", latencyMs);
        report.put("failure", failure == null ? "" : failure);
        if (response != null) {
            report.put("published", Map.of(
                    "outcome", response.outcome().name(),
                    "games", response.games().stream()
                            .map(game -> Map.of(
                                    "bggId", game.game().ranking().bggId(),
                                    "name", game.game().ranking().sourceName(),
                                    "replyPartCount", game.replyParts().size()))
                            .toList(),
                    "modelCalls", response.harness().modelCalls(),
                    "catalogCalls", response.harness().catalogCalls(),
                    "webResearchCalls", response.harness().webResearchCalls(),
                    "fallbackUsed", response.harness().fallbackUsed(),
                    "actions", response.harness().actions()));
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

    private static final class Capture {
        private final String provider;
        private final String model;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private int structuredCallCount;

        private Capture(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        private synchronized int begin(String operation, BoardGameRecommendationModel.Request request) {
            if ("structured_publication".equals(operation)) structuredCallCount++;
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("ordinal", calls.size() + 1);
            call.put("operation", operation);
            call.put("status", "STARTED");
            call.put("messageCharacters", request.messages().stream()
                    .mapToInt(message -> message.content().codePointCount(0, message.content().length()))
                    .sum());
            call.put("toolNames", request.tools().stream()
                    .map(BoardGameRecommendationModel.ToolSpec::name)
                    .toList());
            call.put("maxOutputTokens", request.maxOutputTokens());
            calls.add(Map.copyOf(call));
            return calls.size() - 1;
        }

        private synchronized void complete(int callIndex, Turn turn, long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(callIndex));
            call.put("status", "COMPLETED");
            call.put("latencyMs", latencyMs);
            call.put("assistantText", turn.text() == null ? "" : turn.text());
            call.put("toolCalls", turn.toolCalls().stream()
                    .map(tool -> Map.of("name", tool.name(), "argumentsJson", tool.argumentsJson()))
                    .toList());
            calls.set(callIndex, Map.copyOf(call));
            toolCalls.addAll(turn.toolCalls());
        }

        private synchronized void completeStructured(
                int callIndex,
                BoardGameRecommendationModel.StructuredTurn turn,
                long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(callIndex));
            call.put("status", "COMPLETED");
            call.put("latencyMs", latencyMs);
            call.put("structuredJson", turn.json());
            call.put("completionStatus", turn.completionStatus().name());
            calls.set(callIndex, Map.copyOf(call));
        }

        private synchronized void fail(int callIndex, Throwable failure, long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(callIndex));
            call.put("status", "FAILED");
            call.put("latencyMs", latencyMs);
            call.put("failureType", failure.getClass().getSimpleName());
            calls.set(callIndex, Map.copyOf(call));
        }

        private synchronized ToolCall lastCandidateProducingToolCall() {
            return toolCalls.stream()
                    .filter(call -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(call.name()))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("missing captured catalog action"));
        }

        private synchronized List<ToolCall> toolCalls(String toolName) {
            return toolCalls.stream().filter(call -> toolName.equals(call.name())).toList();
        }

        private synchronized int structuredCallCount() {
            return structuredCallCount;
        }
    }

    private static final class CanaryCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games = List.of(
                        game(101, "River Market", "Open Drafting", "A changing river market."),
                        game(105, "Harbor Chorus", "Simultaneous Action Selection", "A shared harbor festival."),
                        game(108, "Old Harbor", "Auction/Bidding", "Players bid for harbor contracts."))
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.ranking().bggId(), value -> value));

        @Override
        public CandidateSet findCandidates(
                BggGameType requiredType,
                List<BggGameType> suggestedTypes,
                int maximum) {
            return new CandidateSet(games.size(), games.values().stream().limit(maximum).toList());
        }

        @Override
        public CandidateSet searchGames(CatalogFilters filters) {
            List<Game> matches = games.values().stream()
                    .filter(game -> filters.types().isEmpty()
                            || game.ranking().types().stream().anyMatch(filters.types()::contains))
                    .filter(game -> game.details().categories().containsAll(filters.categories()))
                    .filter(game -> game.details().mechanics().containsAll(filters.mechanics()))
                    .filter(game -> game.details().designers().containsAll(filters.designers()))
                    .filter(game -> game.details().publishers().containsAll(filters.publishers()))
                    .filter(game -> game.details().families().containsAll(filters.families()))
                    .filter(game -> filters.minimumPublicationYear() == null
                            || game.ranking().publicationYear() >= filters.minimumPublicationYear())
                    .filter(game -> filters.maximumPublicationYear() == null
                            || game.ranking().publicationYear() <= filters.maximumPublicationYear())
                    .filter(game -> filters.minimumAverageRating() == null
                            || game.ranking().averageRating().compareTo(filters.minimumAverageRating()) >= 0)
                    .filter(game -> filters.minimumRatingsCount() == null
                            || game.ranking().usersRated() >= filters.minimumRatingsCount())
                    .sorted(java.util.Comparator.comparingInt(
                                    (Game game) -> textMatchScore(game, filters.textQuery()))
                            .reversed()
                            .thenComparingInt(game -> game.ranking().bggId()))
                    .toList();
            List<Game> page = matches.stream()
                    .skip(filters.offset())
                    .limit(filters.maximum())
                    .toList();
            return new CandidateSet(matches.size(), page);
        }

        @Override
        public List<Ranking> searchByNames(List<String> names) {
            return games.values().stream()
                    .filter(game -> names.stream().anyMatch(name ->
                            game.ranking().sourceName().equalsIgnoreCase(name)))
                    .map(Game::ranking)
                    .toList();
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> ids) {
            return ids.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }

        private static int textMatchScore(Game game, String query) {
            if (query == null) return 0;
            String searchable = (game.ranking().sourceName() + " "
                            + game.details().description() + " "
                            + String.join(" ", game.details().mechanics()))
                    .toLowerCase(Locale.ROOT);
            return (int) java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                    .filter(searchable::contains)
                    .count();
        }

        private static Game game(int id, String name, String mechanic, String description) {
            Ranking ranking = new Ranking(
                    id,
                    name,
                    2025,
                    id,
                    new BigDecimal("7.2"),
                    new BigDecimal("7.5"),
                    2_000,
                    List.of(BggGameType.STRATEGY));
            Details details = new Details(
                    name,
                    "",
                    "",
                    2,
                    4,
                    75,
                    new BigDecimal("2.5"),
                    List.of("Strategy"),
                    List.of(mechanic),
                    45,
                    75,
                    10,
                    10,
                    "4",
                    "3–4",
                    1,
                    500,
                    List.of(),
                    List.of("Canary Designer"),
                    List.of("Canary Publisher"),
                    description,
                    "");
            return new Game(ranking, details);
        }
    }
}
