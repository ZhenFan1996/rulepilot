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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * One opt-in real-model canary for the ordinary direct recommendation path. Raw provider and
 * tool payloads stay under the ignored {@code .local/} directory.
 */
@Tag("paid-recommendation-canary")
class BoardGameRecommendationAgentPaidCanaryTest {

    private static final Duration RECOMMENDATION_TIMEOUT = Duration.ofMinutes(2);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void repliesToAGreetingNaturallyWithoutUnneededExternalWork() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        String modelName = canaryModel(prefix);
        Capture capture = new Capture(provider, modelName);
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                modelName,
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
        AtomicLong firstAnswerPartMs = new AtomicLong(-1);
        String scenario = environment("RULEPILOT_RECOMMENDATION_ORDINARY_CHAT_SCENARIO", "greeting");
        String message = environment("RULEPILOT_RECOMMENDATION_ORDINARY_CHAT_MESSAGE", "你好");
        capture.beginTurn(scenario);

        try {
            var response = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), message),
                    "zh-CN",
                    null,
                    ignored -> {},
                    text -> {
                        if (!text.isBlank()) firstAnswerPartMs.compareAndSet(-1, elapsed(started));
                    });
            long totalMs = elapsed(started);

            assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(response.assistantMessage()).isNotBlank();
            assertThat(response.harness().modelCalls()).isEqualTo(1);
            assertThat(response.harness().catalogCalls()).isZero();
            assertThat(response.harness().webResearchCalls()).isZero();
            assertThat(firstAnswerPartMs.get()).isNotNegative();
            assertThat(totalMs).isPositive();
            writeArtifact(scenario, capture, response, totalMs, null);
        } catch (Throwable failure) {
            writeArtifact(scenario, capture, null, elapsed(started), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void followsUpAndReplacesGamesInAWorkerPlacementConversation() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        String modelName = canaryModel(prefix);
        Capture capture = new Capture(provider, modelName);
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                modelName,
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), RECOMMENDATION_TIMEOUT);
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), configuredResearchCanary()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        long started = System.nanoTime();
        CanaryConversation conversation = new CanaryConversation(agent, capture);

        try {
            CanaryTurn opening = conversation.turn(
                    "worker-opening",
                    "我们三个人想玩一些工人放置的德式重策，有什么推荐？",
                    List.of());
            assertThat(opening.response().outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(opening.response().games()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(game -> {
                assertThat(game.game().details().minPlayers()).isLessThanOrEqualTo(3);
                assertThat(game.game().details().maxPlayers()).isGreaterThanOrEqualTo(3);
                assertThat(game.game().details().averageWeight())
                        .isGreaterThanOrEqualTo(new BigDecimal("3.0"));
                assertThat(game.game().details().mechanics()).contains("Worker Placement");
            });
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL, "worker-opening"))
                    .hasSize(1);
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RECOMMEND_TOOL, "worker-opening"))
                    .hasSize(1);
            JsonNode openingSearch = json.readTree(capture
                    .toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL, "worker-opening")
                    .getFirst()
                    .argumentsJson());
            assertThat(openingSearch.path("requiredMechanics").toString())
                    .isEqualTo("[\"Worker Placement\"]");
            int openingRequestedCount = openingSearch.path("publicationCount").asInt();
            assertThat(openingRequestedCount).isGreaterThanOrEqualTo(opening.response().games().size());
            if (openingRequestedCount > opening.response().games().size()) {
                assertThat(opening.response().shortfall())
                        .isEqualTo(new BoardGameRecommendationAgent.RecommendationShortfall(
                                openingRequestedCount, opening.response().games().size()));
            }
            assertThat(openingSearch.path("players").asInt()).isEqualTo(3);
            assertThat(openingSearch.path("complexity").path("minimum").decimalValue())
                    .isGreaterThanOrEqualTo(new BigDecimal("3.0"));

            CanaryTurn comparison = conversation.turn(
                    "worker-comparison",
                    "这几款里哪款三人体验最好？先别换新游戏，就在这几款里把取舍说人话。",
                    List.of());
            assertThat(comparison.response().outcome()).isNotEqualTo(Outcome.UNAVAILABLE);
            assertThat(comparison.response().assistantMessage()).isNotBlank();
            assertThat(comparison.response().harness().catalogCalls()).isZero();

            List<Integer> alreadyShown = conversation.shownIds();
            CanaryTurn replacement = conversation.turn(
                    "worker-replacement",
                    "第二款听着有点闷，给我换成一款互动更强的工放，别重复前面这些。",
                    alreadyShown);
            assertThat(replacement.response().outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(replacement.response().games())
                    .hasSize(1)
                    .allSatisfy(game -> assertThat(game.game().ranking().bggId())
                            .isNotIn(alreadyShown));
            JsonNode replacementSearch = json.readTree(capture
                    .toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL, "worker-replacement")
                    .getFirst()
                    .argumentsJson());
            assertThat(replacementSearch.path("publicationCount").asInt()).isEqualTo(1);
            assertThat(replacementSearch.path("experienceQuestion").asText()).isNotBlank();
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RESEARCH_TOOL, "worker-replacement"))
                    .isEmpty();
            assertThat(replacement.response().harness().webResearchCalls()).isEqualTo(1);

            for (String turn : List.of("worker-opening", "worker-comparison", "worker-replacement")) {
                assertThat(capture.toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL, turn))
                        .hasSizeLessThanOrEqualTo(1);
                assertThat(capture.toolCalls(BoardGameRecommendationAgent.RESEARCH_TOOL, turn))
                        .hasSizeLessThanOrEqualTo(1);
                assertThat(capture.toolCalls(BoardGameRecommendationAgent.RECOMMEND_TOOL, turn))
                        .hasSizeLessThanOrEqualTo(1);
            }
            assertThat(conversation.turns())
                    .allSatisfy(turn -> {
                        assertThat(turn.latencyMs()).isLessThan(RECOMMENDATION_TIMEOUT.toMillis());
                        assertThat(turn.response().harness().actions())
                                .noneMatch(action -> action.startsWith("REJECTED_")
                                        || action.startsWith("PUBLICATION_FAILED:")
                                        || action.startsWith("REPEATED_")
                                        || action.equals("RUN_DEADLINE_EXCEEDED"));
                        if (turn.response().harness().fallbackUsed()) {
                            assertThat(turn.response().outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
                            assertThat(turn.response().harness().actions())
                                    .contains("RECOMMENDATION_NARRATIVE_PARTIAL");
                        }
                    });
            writeConversationArtifact(
                    "worker-placement-followups",
                    capture,
                    conversation.turns(),
                    elapsed(started),
                    null);
        } catch (Throwable failure) {
            writeConversationArtifact(
                    "worker-placement-followups",
                    capture,
                    conversation.turns(),
                    elapsed(started),
                    failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void publishesForAnOrdinaryMoodBasedRequest() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        String modelName = canaryModel(prefix);
        Capture capture = new Capture(provider, modelName);
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                modelName,
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), RECOMMENDATION_TIMEOUT);
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), configuredResearchCanary()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        long started = System.nanoTime();
        AtomicLong firstRecommendationPartMs = new AtomicLong(-1);
        capture.beginTurn("playful-party");

        try {
            var response = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "今晚四个人刚加完班，脑容量只够一杯奶茶，但又不想各玩各的；想找一款能互相吐槽、最好一小时内收掉的，输了也能笑，来点什么？"),
                    "zh-CN",
                    null,
                    ignored -> {},
                    ignored -> {},
                    part -> {
                        long observed = elapsed(started);
                        firstRecommendationPartMs.compareAndSet(-1, observed);
                        capture.firstRecommendationPart(observed);
                    });
            long totalMs = elapsed(started);

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.games()).isNotEmpty();
            assertThat(response.assistantMessage()).isNotBlank();
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL)).hasSize(1);
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RECOMMEND_TOOL)).hasSize(1);
            JsonNode search = json.readTree(capture
                    .toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL)
                    .getFirst()
                    .argumentsJson());
            assertThat(search.path("publicationCount").asInt())
                    .isGreaterThanOrEqualTo(response.games().size());
            assertThat(search.path("experienceQuestion").asText()).isNotBlank();
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RESEARCH_TOOL))
                    .isEmpty();
            assertThat(response.harness().webResearchCalls()).isEqualTo(1);
            assertThat(firstRecommendationPartMs.get()).isBetween(0L, totalMs - 1);
            assertThat(totalMs).isLessThan(RECOMMENDATION_TIMEOUT.toMillis());
            writeArtifact("playful-party", capture, response, totalMs, null);
        } catch (Throwable failure) {
            writeArtifact(
                    "playful-party", capture, null, elapsed(started), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void keepsAnExplicitCooperativeRequestInsideTheTypedPlayModeBoundary() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        String modelName = canaryModel(prefix);
        Capture capture = new Capture(provider, modelName);
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                modelName,
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), RECOMMENDATION_TIMEOUT);
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), configuredResearchCanary()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        long started = System.nanoTime();
        capture.beginTurn("candlelight-cooperative");

        try {
            var response = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "我和对象周末想关灯点蜡烛玩桌游；要两个人纯合作、有点故事感、九十分钟以内，别让我们互相甩锅。"),
                    "zh-CN");
            long totalMs = elapsed(started);

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.COOPERATIVE);
            assertThat(response.games()).isNotEmpty().allSatisfy(game -> {
                assertThat(game.game().details().mechanics()).contains("Cooperative Game");
                assertThat(game.game().details().minPlayers()).isLessThanOrEqualTo(2);
                assertThat(game.game().details().maxPlayers()).isGreaterThanOrEqualTo(2);
                assertThat(game.game().details().maximumPlayTimeMinutes()).isLessThanOrEqualTo(90);
            });
            JsonNode search = json.readTree(capture
                    .toolCalls(BoardGameRecommendationAgent.SEARCH_TOOL)
                    .getFirst()
                    .argumentsJson());
            assertThat(search.path("requiredInteraction").asText()).isEqualTo("COOPERATIVE");
            assertThat(search.path("descriptionQuery").asText()).isNotBlank();
            assertThat(capture.toolCalls(BoardGameRecommendationAgent.RECOMMEND_TOOL)).hasSize(1);
            JsonNode publication = json.readTree(capture
                    .toolCalls(BoardGameRecommendationAgent.RECOMMEND_TOOL)
                    .getFirst()
                    .argumentsJson());
            List<String> citedEvidenceIds = new ArrayList<>();
            publication.path("selections").forEach(selection -> selection.path("internalEvidenceIds")
                    .forEach(id -> citedEvidenceIds.add(id.asText())));
            assertThat(citedEvidenceIds).anyMatch(id -> id.endsWith(":publisherDescription"));
            assertThat(response.assistantMessage()).isNotBlank().isEqualTo(publication.path("playerReply").asText());
            assertThat(totalMs).isLessThan(RECOMMENDATION_TIMEOUT.toMillis());
            writeArtifact("candlelight-cooperative", capture, response, totalMs, null);
        } catch (Throwable failure) {
            writeArtifact(
                    "candlelight-cooperative",
                    capture,
                    null,
                    elapsed(started),
                    failure.getClass().getSimpleName());
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
        String publicationModelName = System.getenv("RULEPILOT_RECOMMENDATION_CANARY_PUBLICATION_MODEL");
        publicationModelName = publicationModelName == null || publicationModelName.isBlank()
                ? modelName
                : publicationModelName.strip();
        capture.publicationModel = publicationModelName;
        ChatModelFactory factory = new ChatModelFactory(ObservationRegistry.NOOP, RECOMMENDATION_TIMEOUT);
        BoardGameRecommendationModel delegate = modelDelegate(
                provider,
                modelName,
                publicationModelName,
                factory.create(provider, apiKey, baseUrl, modelName));
        String selectedPublicationModelName = publicationModelName;
        return new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(BoardGameRecommendationModel.Request request) {
                boolean publicationTurn = request.toolChoice() == BoardGameRecommendationModel.ToolChoice.REQUIRED
                        && request.tools().size() == 1;
                String selectedModel = publicationTurn ? selectedPublicationModelName : modelName;
                long started = System.nanoTime();
                int callIndex = capture.begin("react", selectedModel, request);
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
            public Turn nextStreaming(
                    BoardGameRecommendationModel.Request request,
                    String ownerUsername,
                    java.util.function.Consumer<ToolCall> accumulatedActionListener) {
                String selectedModel = request.toolChoice() == BoardGameRecommendationModel.ToolChoice.REQUIRED
                                && request.tools().size() == 1
                        ? selectedPublicationModelName
                        : modelName;
                long started = System.nanoTime();
                int callIndex = capture.begin("react_stream", selectedModel, request);
                AtomicLong firstOutputMs = new AtomicLong(-1);
                try {
                    Turn result = delegate.nextStreaming(request, null, action -> {
                        long observed = elapsed(started);
                        if (firstOutputMs.compareAndSet(-1, observed)) {
                            capture.firstStreamOutput(callIndex, observed);
                        }
                        accumulatedActionListener.accept(action);
                    });
                    capture.complete(callIndex, result, elapsed(started));
                    return result;
                } catch (RuntimeException | Error failure) {
                    capture.fail(callIndex, failure, elapsed(started));
                    throw failure;
                }
            }
        };
    }

    private BoardGameRecommendationModel modelDelegate(
            String provider,
            String modelName,
            String publicationModelName,
            ChatModel chatModel) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        var resolvedModel = new RuntimeModelConfiguration.ResolvedModel(
                chatModel, provider, modelName, "deepseek".equals(provider), true);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(resolvedModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(modelName);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider));
        double temperature = Double.parseDouble(
                environment("RULEPILOT_RECOMMENDATION_CANARY_TEMPERATURE", "0.0"));
        return new SpringAiBoardGameRecommendationModel(
                configuration,
                temperature,
                publicationModelName,
                Duration.parse(environment("RULEPILOT_RECOMMENDATION_CANARY_HEDGE_DELAY", "PT8S")));
    }

    private BoardGameRecommendationWebResearch configuredResearchThatMustNotRun() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("a useful selectable slate must proceed directly to its terminal model action");
            }
        };
    }

    private BoardGameRecommendationWebResearch configuredResearchCanary() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                List<BoardGameRecommendationWebResearch.GameResearch> games = request.candidates().stream()
                        .map(candidate -> new BoardGameRecommendationWebResearch.GameResearch(
                                candidate.bggId(),
                                List.of(new BoardGameRecommendationWebResearch.Observation(
                                        reportedExperience(candidate.bggId()),
                                        List.of(1)))))
                        .toList();
                return Optional.of(new Research(
                        games,
                        List.of(new BoardGameRecommendationWebResearch.Source(
                                1,
                                "RulePilot canary experience report",
                                "https://canary.rulepilot.example/recommendation-experience",
                                "canary.rulepilot.example"))));
            }
        };
    }

    private String reportedExperience(int bggId) {
        return switch (bggId) {
            case 202 -> "Canary players described a mostly parallel puzzle with little blocking at three players.";
            case 201, 203, 205, 207, 209 ->
                "Canary players reported frequent competition for shared worker spaces and visible timing pressure at three players.";
            case 204, 206, 208, 210 ->
                "Canary players reported occasional blocking, with most tension coming from contested contracts and turn order.";
            case 301, 302 ->
                "Canary players reported constant table talk, quick turns, and plenty of room for joking after a tiring workday.";
            default ->
                "Canary participants reported that a mixed-experience group could learn the opening turns together.";
        };
    }

    private void writeArtifact(
            String scenario,
            Capture capture,
            BoardGameRecommendationAgent.ConversationResponse response,
            long latencyMs,
            String failure) throws Exception {
        Map<String, Object> report = baseReport(capture, latencyMs, failure);
        if (response != null) report.put("published", publishedReport(response));
        writeReport(scenario, report);
    }

    private void writeConversationArtifact(
            String scenario,
            Capture capture,
            List<CanaryTurn> turns,
            long latencyMs,
            String failure) throws Exception {
        Map<String, Object> report = baseReport(capture, latencyMs, failure);
        report.put("turns", turns.stream().map(turn -> Map.of(
                        "label", turn.label(),
                        "userMessage", turn.userMessage(),
                        "latencyMs", turn.latencyMs(),
                        "published", publishedReport(turn.response())))
                .toList());
        writeReport(scenario, report);
    }

    private Map<String, Object> baseReport(Capture capture, long latencyMs, String failure) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("generatedAt", Instant.now().toString());
        report.put("provider", capture.provider);
        report.put("model", capture.model);
        report.put("publicationModel", capture.publicationModel);
        report.put("temperature", Double.parseDouble(
                environment("RULEPILOT_RECOMMENDATION_CANARY_TEMPERATURE", "0.0")));
        report.put("rawModelCalls", capture.calls);
        if (capture.firstRecommendationPartMs >= 0) {
            report.put("firstRecommendationPartMs", capture.firstRecommendationPartMs);
        }
        report.put("latencyMs", latencyMs);
        report.put("failure", failure == null ? "" : failure);
        return report;
    }

    private Map<String, Object> publishedReport(
            BoardGameRecommendationAgent.ConversationResponse response) {
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("outcome", response.outcome().name());
        published.put("assistantMessage", response.assistantMessage());
        published.put("assistantMessageCharacters", codePoints(response.assistantMessage()));
        published.put("games", response.games().stream()
                .map(game -> Map.of(
                        "bggId", game.game().ranking().bggId(),
                        "name", game.game().ranking().sourceName()))
                .toList());
        published.put("modelCalls", response.harness().modelCalls());
        published.put("catalogCalls", response.harness().catalogCalls());
        published.put("webResearchCalls", response.harness().webResearchCalls());
        published.put("fallbackUsed", response.harness().fallbackUsed());
        published.put("actions", response.harness().actions());
        return Map.copyOf(published);
    }

    private void writeReport(String scenario, Map<String, Object> report) throws Exception {
        String label = environment("RULEPILOT_RECOMMENDATION_CANARY_LABEL", "current")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path output = Path.of(System.getProperty("user.dir"))
                .getParent()
                .resolve(".local/agent-evaluation/recommendation-paid-canary-" + label + "-" + scenario + ".json");
        Files.createDirectories(output.getParent());
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

    private String canaryModel(String providerPrefix) {
        String candidate = System.getenv("RULEPILOT_RECOMMENDATION_CANARY_MODEL");
        return candidate == null || candidate.isBlank()
                ? environment(providerPrefix + "_MODEL", null)
                : candidate.strip();
    }

    private static long elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private record CanaryTurn(
            String label,
            String userMessage,
            BoardGameRecommendationAgent.ConversationResponse response,
            long latencyMs) {}

    private static final class CanaryConversation {
        private final BoardGameRecommendationAgent agent;
        private final Capture capture;
        private final List<DialogueMessage> transcript = new ArrayList<>();
        private final Map<Integer, KnownGame> knownGames = new LinkedHashMap<>();
        private final LinkedHashSet<Integer> shownIds = new LinkedHashSet<>();
        private final Map<Integer, Game> verifiedGames = new LinkedHashMap<>();
        private final List<CanaryTurn> turns = new ArrayList<>();
        private RecommendationProfile profile = RecommendationProfile.empty();

        private CanaryConversation(BoardGameRecommendationAgent agent, Capture capture) {
            this.agent = agent;
            this.capture = capture;
        }

        private CanaryTurn turn(String label, String message, List<Integer> excludedIds) {
            transcript.add(new DialogueMessage("user", message));
            ConversationRequest request = new ConversationRequest(
                    profile,
                    message,
                    excludedIds,
                    List.copyOf(transcript),
                    null,
                    List.copyOf(knownGames.values()),
                    List.copyOf(shownIds),
                    List.copyOf(verifiedGames.values()));
            AtomicReference<BoardGameRecommendationAgent.TurnCheckpoint> checkpoint = new AtomicReference<>();
            capture.beginTurn(label);
            long started = System.nanoTime();
            var response = agent.conversePersisted(
                    request,
                    "zh-CN",
                    null,
                    ignored -> {},
                    checkpoint::set);
            CanaryTurn turn = new CanaryTurn(label, message, response, elapsed(started));
            turns.add(turn);

            if (checkpoint.get() != null) {
                profile = checkpoint.get().profile();
                checkpoint.get().verifiedGames().forEach(this::rememberVerified);
            }
            if (response.outcome() != Outcome.UNAVAILABLE) {
                transcript.add(new DialogueMessage("assistant", response.assistantMessage()));
                profile = response.profile();
                response.games().stream()
                        .map(BoardGameRecommendationAgent.RecommendedGame::game)
                        .forEach(this::rememberShown);
                if (response.comparison() != null) {
                    response.comparison().candidates().stream()
                            .map(BoardGameRecommendationAgent.ComparisonCandidate::game)
                            .forEach(this::rememberShown);
                }
            }
            return turn;
        }

        private void rememberShown(Game game) {
            rememberVerified(game);
            shownIds.add(game.ranking().bggId());
        }

        private void rememberVerified(Game game) {
            int bggId = game.ranking().bggId();
            verifiedGames.putIfAbsent(bggId, game);
            knownGames.putIfAbsent(bggId, new KnownGame(
                    bggId,
                    game.details().name(),
                    game.ranking().sourceName()));
        }

        private List<Integer> shownIds() {
            return List.copyOf(shownIds);
        }

        private List<CanaryTurn> turns() {
            return List.copyOf(turns);
        }
    }

    private static final class Capture {
        private final String provider;
        private final String model;
        private String publicationModel;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final List<CapturedToolCall> toolCalls = new ArrayList<>();
        private String currentTurn = "unlabeled";
        private long firstRecommendationPartMs = -1;

        private Capture(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        private synchronized void beginTurn(String label) {
            currentTurn = label;
        }

        private synchronized int begin(
                String operation,
                String modelName,
                BoardGameRecommendationModel.Request request) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("ordinal", calls.size() + 1);
            call.put("turn", currentTurn);
            call.put("operation", operation);
            call.put("model", modelName);
            call.put("status", "STARTED");
            call.put("messageCharacters", request.messages().stream()
                    .mapToInt(message -> message.content().codePointCount(0, message.content().length()))
                    .sum());
            call.put("toolDefinitionCharacters", request.tools().stream()
                    .mapToInt(tool -> codePoints(tool.name())
                            + codePoints(tool.description())
                            + codePoints(tool.inputSchema()))
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
            call.put("promptTokens", turn.promptTokens());
            call.put("completionTokens", turn.completionTokens());
            call.put("assistantText", turn.text() == null ? "" : turn.text());
            call.put("toolCalls", turn.toolCalls().stream()
                    .map(tool -> Map.of("name", tool.name(), "argumentsJson", tool.argumentsJson()))
                    .toList());
            calls.set(callIndex, Map.copyOf(call));
            String turnLabel = String.valueOf(call.get("turn"));
            turn.toolCalls().forEach(tool -> toolCalls.add(new CapturedToolCall(turnLabel, tool)));
        }

        private synchronized void firstStreamOutput(int callIndex, long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(callIndex));
            call.put("firstStreamOutputMs", latencyMs);
            calls.set(callIndex, Map.copyOf(call));
        }

        private synchronized void firstRecommendationPart(long latencyMs) {
            if (firstRecommendationPartMs < 0) firstRecommendationPartMs = latencyMs;
        }

        private synchronized void fail(int callIndex, Throwable failure, long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(callIndex));
            call.put("status", "FAILED");
            call.put("latencyMs", latencyMs);
            call.put("failureType", failure.getClass().getSimpleName());
            calls.set(callIndex, Map.copyOf(call));
        }

        private synchronized List<ToolCall> toolCalls(String toolName) {
            return toolCalls.stream()
                    .map(CapturedToolCall::call)
                    .filter(call -> toolName.equals(call.name()))
                    .toList();
        }

        private synchronized List<ToolCall> toolCalls(String toolName, String turn) {
            return toolCalls.stream()
                    .filter(call -> turn.equals(call.turn()))
                    .map(CapturedToolCall::call)
                    .filter(call -> toolName.equals(call.name()))
                    .toList();
        }

        private record CapturedToolCall(String turn, ToolCall call) {}
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static final class CanaryCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games = List.of(
                        game(201, "River Market", BggGameType.STRATEGY, 2, 4, 120, "3.6", "Worker Placement",
                                "Workers compete for a changing shared market and scarce river contracts."),
                        game(202, "Quiet Abbey", BggGameType.STRATEGY, 1, 4, 105, "3.5", "Worker Placement",
                                "Players build efficient abbey engines with limited direct blocking."),
                        game(203, "Iron Orchard", BggGameType.STRATEGY, 2, 4, 135, "3.8", "Worker Placement",
                                "A tight agricultural economy where occupied spaces reshape everyone else's timing."),
                        game(204, "Guild Foundry", BggGameType.STRATEGY, 2, 4, 110, "3.4", "Worker Placement",
                                "Guild contracts reward careful sequencing and occasional worker-space denial."),
                        game(205, "Canal Council", BggGameType.STRATEGY, 2, 5, 125, "3.7", "Worker Placement",
                                "Players contest canal offices, turn order, and shared infrastructure bonuses."),
                        game(206, "Copper Commune", BggGameType.STRATEGY, 2, 4, 140, "4.0", "Worker Placement",
                                "A dense production puzzle around shared contracts and long-term planning."),
                        game(207, "Stone Ledger", BggGameType.STRATEGY, 2, 4, 115, "3.6", "Worker Placement",
                                "Blocking a workshop can force rivals to reroute an entire production chain."),
                        game(208, "Alpine Workshop", BggGameType.STRATEGY, 1, 4, 105, "3.3", "Worker Placement",
                                "A brisk workshop economy with contested orders and moderate blocking."),
                        game(209, "Dune Syndicate", BggGameType.STRATEGY, 2, 4, 130, "3.9", "Worker Placement",
                                "Shared action spaces and shifting initiative create sharp three-player competition."),
                        game(210, "Clockwork Estates", BggGameType.STRATEGY, 2, 4, 120, "3.7", "Worker Placement",
                                "Players balance estate engines against contested public commissions."),
                        game(301, "Midnight Alibi", BggGameType.PARTY, 3, 8, 40, "1.2", "Storytelling",
                                "Players invent ridiculous alibis, challenge details, and keep everyone talking."),
                        game(302, "Sofa Detectives", BggGameType.PARTY, 3, 6, 55, "1.6", "Deduction",
                                "Fast clues and playful accusations make a low-rules after-work mystery."),
                        game(303, "Snack Tribunal", BggGameType.PARTY, 3, 8, 35, "1.3", "Voting",
                                "Players debate absurd cases and vote on the most entertaining defense."),
                        game(304, "Office Cryptids", BggGameType.PARTY, 4, 10, 45, "1.5", "Acting",
                                "Short improvised scenes turn familiar workplace mishaps into playful monsters."),
                        game(305, "Whisper Relay", BggGameType.PARTY, 4, 12, 30, "1.1", "Communication Limits",
                                "Teams pass strange clues through a noisy chain and compare the final result."),
                        game(306, "Tiny Roast", BggGameType.PARTY, 3, 8, 25, "1.2", "Judging Games",
                                "Quick prompts invite friendly jokes while keeping every player involved."),
                        game(307, "Moonlight Headlines", BggGameType.PARTY, 3, 7, 50, "1.7", "Storytelling",
                                "Players combine unlikely headlines and explain the ridiculous events behind them."),
                        game(308, "Last Train Banter", BggGameType.PARTY, 4, 9, 40, "1.4", "Team-Based Game",
                                "Teams trade fast clues and playful interruptions during short timed rounds."),
                        game(401, "Harbor Chorus", BggGameType.STRATEGY, 2, 4, 75, "2.5",
                                "Simultaneous Action Selection", "A shared harbor festival."),
                        game(402, "Old Harbor", BggGameType.STRATEGY, 2, 4, 75, "2.5", "Auction/Bidding",
                                "Players bid for harbor contracts."),
                        game(501, "Shared Ember", BggGameType.STRATEGY, 2, 2, 70, "2.4", "Cooperative Game",
                                "Two players share limited actions while following a complete fireside mystery."),
                        game(502, "Lantern Pact", BggGameType.FAMILY, 1, 4, 55, "1.9", "Cooperative Game",
                                "Players jointly solve a sequence of narrative lantern challenges."),
                        game(503, "Rival Grove", BggGameType.STRATEGY, 2, 4, 60, "2.3", "Area Majority / Influence",
                                "Players compete to control a woodland map."))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.ranking().bggId(),
                        value -> value,
                        (first, ignored) -> first,
                        LinkedHashMap::new));

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

        private static Game game(
                int id,
                String name,
                BggGameType type,
                int minPlayers,
                int maxPlayers,
                int minutes,
                String weight,
                String mechanic,
                String description) {
            Ranking ranking = new Ranking(
                    id,
                    name,
                    2025,
                    id,
                    new BigDecimal("7.2"),
                    new BigDecimal("7.5"),
                    2_000,
                    List.of(type));
            Details details = new Details(
                    name,
                    "",
                    "",
                    minPlayers,
                    maxPlayers,
                    minutes,
                    new BigDecimal(weight),
                    List.of(type == BggGameType.PARTY ? "Party Game" : "Strategy"),
                    List.of(mechanic),
                    Math.max(20, minutes - 25),
                    minutes,
                    10,
                    10,
                    "3",
                    minPlayers + "–" + maxPlayers,
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
