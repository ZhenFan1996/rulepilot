package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.adapter.out.model.SpringAiBoardGameRecommendationModel;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

@Tag("real-recommendation-agent-evaluation")
class BoardGameRecommendationAgentRealConversationEvaluationTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesANaturalTitleCorrectionAcrossPaidProviders() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RECOMMENDATION_AGENT_EVAL")));
        List<Map<String, Object>> results = new ArrayList<>();

        for (String providerName : List.of("deepseek", "qwen")) {
            Provider provider = provider(providerName);
            assertThat(prohibitedModel(provider.model())).isFalse();
            results.add(runConversation(provider));
        }

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result).containsEntry("outcome", "RECOMMENDATIONS")
                    .containsEntry("continuationResolved", true)
                    .containsEntry("fallbackUsed", false);
            assertThat((Integer) result.get("recommendationCount")).isGreaterThanOrEqualTo(2);
            assertThat((Long) result.get("totalLatencyMs")).isLessThanOrEqualTo(30_000L);
        });

        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path output = root.resolve(".local/agent-evaluation/recommendation-conversation-real.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "explicitPreferenceEnumInjected", false,
                        "rawModelOutputStored", false,
                        "prohibitedQwenPlusUsed", false))) + "\n", StandardCharsets.UTF_8);
    }

    private Map<String, Object> runConversation(Provider provider) {
        UnlockableCatalog catalog = new UnlockableCatalog();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            String opening = "我想找和《马赛克花园》机制接近的游戏。";
            long openingStarted = System.nanoTime();
            var first = agent.converse(new ConversationRequest(RecommendationProfile.empty(), opening), "zh-CN");
            long openingLatencyMs = Duration.ofNanos(System.nanoTime() - openingStarted).toMillis();
            assertThat(first.outcome()).isIn(Outcome.NEEDS_CLARIFICATION, Outcome.CONVERSATION);
            assertThat(first.assistantMessage()).isNotBlank();
            assertThat(first.harness().fallbackUsed()).isFalse();
            assertThat(openingLatencyMs).isLessThanOrEqualTo(30_000L);

            catalog.unlock();
            String correction = "Mosaic Field";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", opening),
                    new DialogueMessage("assistant", first.assistantMessage()),
                    new DialogueMessage("user", correction));
            long correctionStarted = System.nanoTime();
            var second = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            correction,
                            List.of(),
                            transcript,
                            null,
                            List.of(),
                            List.of()),
                    "zh-CN");
            long correctionLatencyMs = Duration.ofNanos(System.nanoTime() - correctionStarted).toMillis();

            assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(second.games()).hasSizeBetween(2, 3);
            assertThat(second.games()).extracting(game -> game.game().ranking().bggId()).doesNotHaveDuplicates();
            boolean continuationResolved = catalog.resolvedTitle(correction);
            assertThat(continuationResolved).isTrue();
            assertThat(catalog.inspectedTitle(correction))
                    .as("a player-authored correction is not mixed into Agent-generated candidates")
                    .isFalse();
            boolean referenceExcluded = second.games().stream()
                    .noneMatch(game -> game.game().ranking().bggId() == 50);
            boolean referenceGrounded = second.games().stream()
                    .allMatch(game -> game.matches().stream()
                            .anyMatch(match -> match.startsWith("与参考游戏共有的 BGG 机制/类型：")));
            assertThat(referenceExcluded).as("the comparison target is not a recommendation").isTrue();
            assertThat(referenceGrounded).as("each card is compared with the corrected reference facts").isTrue();
            assertThat(second.harness().actions()).contains("RECOMMEND_GAMES");
            assertThat(second.harness().fallbackUsed()).isFalse();
            assertThat(correctionLatencyMs).isLessThanOrEqualTo(30_000L);

            Set<Integer> selected = new LinkedHashSet<>();
            second.games().forEach(game -> selected.add(game.game().ranking().bggId()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", "cx-rec-" + provider.name());
            result.put("provider", provider.name());
            result.put("model", provider.model());
            result.put("outcome", second.outcome().name());
            result.put("continuationResolved", continuationResolved);
            result.put("referenceExcluded", referenceExcluded);
            result.put("referenceGrounded", referenceGrounded);
            result.put("naturalTurnCount", transcript.size());
            result.put("recommendationCount", second.games().size());
            result.put("uniqueRecommendationCount", selected.size());
            result.put("modelCalls", Math.max(first.harness().modelCalls(), second.harness().modelCalls()));
            result.put("catalogCalls", Math.max(first.harness().catalogCalls(), second.harness().catalogCalls()));
            result.put("webResearchCalls", Math.max(
                    first.harness().webResearchCalls(), second.harness().webResearchCalls()));
            result.put("conversationModelCalls", first.harness().modelCalls() + second.harness().modelCalls());
            result.put("fallbackUsed", first.harness().fallbackUsed() || second.harness().fallbackUsed());
            result.put("totalLatencyMs", Math.max(openingLatencyMs, correctionLatencyMs));
            result.put("conversationLatencyMs", openingLatencyMs + correctionLatencyMs);
            result.put("actions", java.util.stream.Stream.concat(
                            first.harness().actions().stream(), second.harness().actions().stream())
                    .distinct()
                    .toList());
            return Map.copyOf(result);
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private SpringAiBoardGameRecommendationModel model(Provider provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(30))
                .create(provider.name(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider.name());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider.name()));
        return new SpringAiBoardGameRecommendationModel(configuration);
    }

    private Provider provider(String name) {
        String prefix = name.toUpperCase(Locale.ROOT);
        return new Provider(
                name,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private boolean prohibitedModel(String model) {
        String normalized = model.toLowerCase(Locale.ROOT);
        return normalized.equals("qwen-plus")
                || normalized.startsWith("qwen-plus-")
                || normalized.startsWith("qwen-plus_");
    }

    private BoardGameRecommendationWebResearch noResearch() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }
        };
    }

    private static Game game(
            int id,
            String name,
            List<String> categories,
            List<String> mechanics,
            BigDecimal weight) {
        return new Game(
                new Ranking(
                        id,
                        name,
                        2024,
                        id,
                        new BigDecimal("7.5"),
                        new BigDecimal("7.8"),
                        1_000),
                new Details(
                        name,
                        "",
                        "",
                        2,
                        4,
                        60,
                        weight,
                        categories,
                        mechanics,
                        35,
                        60,
                        10,
                        10,
                        "Best with 4 players",
                        "Recommended with 2–4 players",
                        2,
                        100,
                        List.of("Spatial Games"),
                        List.of("Designer A"),
                        List.of("Publisher A")));
    }

    private record Provider(String name, String apiKey, String baseUrl, String model) {}

    private static final class UnlockableCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games;
        private final Map<String, Integer> names;
        private final Set<String> inspectedTitles = new LinkedHashSet<>();
        private final Set<String> resolvedTitles = new LinkedHashSet<>();
        private boolean unlocked;

        private UnlockableCatalog() {
            Map<Integer, Game> values = new LinkedHashMap<>();
            values.put(50, game(
                    50,
                    "Mosaic Field",
                    List.of("Abstract Strategy"),
                    List.of("Pattern Building", "Tile Placement", "Open Drafting"),
                    new BigDecimal("2.2")));
            values.put(60, game(
                    60,
                    "Glass Orchard",
                    List.of("Abstract Strategy"),
                    List.of("Pattern Building", "Open Drafting"),
                    new BigDecimal("2.3")));
            values.put(61, game(
                    61,
                    "Loom City",
                    List.of("Abstract Strategy"),
                    List.of("Tile Placement", "Pattern Building"),
                    new BigDecimal("2.6")));
            values.put(62, game(
                    62,
                    "Prism Workshop",
                    List.of("Strategy"),
                    List.of("Open Drafting", "Contracts"),
                    new BigDecimal("2.8")));
            games = Map.copyOf(values);
            names = Map.of(
                    "Mosaic Field", 50,
                    "Glass Orchard", 60,
                    "Loom City", 61,
                    "Prism Workshop", 62);
        }

        private void unlock() {
            unlocked = true;
        }

        private boolean inspectedTitle(String title) {
            return inspectedTitles.contains(title);
        }

        private boolean resolvedTitle(String title) {
            return resolvedTitles.contains(title);
        }

        @Override
        public CandidateSet findCandidates(
                BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            return new CandidateSet(
                    unlocked ? games.size() : 0,
                    unlocked ? games.values().stream().limit(maximum).toList() : List.of());
        }

        @Override
        public List<Ranking> searchByNames(List<String> titles) {
            if (!unlocked) return List.of();
            inspectedTitles.addAll(titles);
            return titles.stream()
                    .map(names::get)
                    .filter(java.util.Objects::nonNull)
                    .map(games::get)
                    .map(Game::ranking)
                    .toList();
        }

        @Override
        public List<Game> resolveReferenceTitle(String title) {
            if (!unlocked) return List.of();
            resolvedTitles.add(title);
            Integer id = names.get(title);
            return id == null ? List.of() : List.of(games.get(id));
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            if (!unlocked) return List.of();
            return bggIds.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public int gameCount() {
            return unlocked ? games.size() : 0;
        }
    }
}
