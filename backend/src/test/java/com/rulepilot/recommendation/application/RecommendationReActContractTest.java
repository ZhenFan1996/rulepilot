package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogFilters;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecommendationReActContractTest {

    @Test
    void oneSearchContractIgnoresInheritedProfileAndPublishesModelAuthoredExplanations() throws Exception {
        Game groveRoutes = game(501, "Grove Routes", BggGameType.FAMILY, 2, 4, 45, "1.8");
        Game groveLines = game(502, "Grove Lines", BggGameType.STRATEGY, 2, 5, 55, "2.4");
        Game expansion = game(503, "Grove Routes: New Paths", BggGameType.EXPANSION, 2, 4, 35, "1.9");
        Game unrelated = game(504, "Desert Signals", BggGameType.FAMILY, 2, 4, 40, "1.7");
        RecordingCatalog catalog = new RecordingCatalog(groveRoutes, groveLines, expansion, unrelated);
        ScriptedModel model = new ScriptedModel(
                action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"includeTypes\":[],\"excludeTypes\":[\"EXPANSION\"],"
                                + "\"title\":{\"match\":\"CONTAINS\",\"value\":\"Grove\"},"
                                + "\"players\":2,\"maxMinutes\":60,\"complexity\":{\"minimum\":1,\"maximum\":3,\"unit\":\"BGG_WEIGHT\"},"
                                + "\"clientTrace\":\"additive-field\"}"),
                action(
                        "compare",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[501,502],\"subjects\":[\"mechanics\",\"durationMinutes\"]}"),
                action(
                        "publish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"这两款都符合本轮条件；前者更轻快，后者更偏策略。\","
                                + "\"playerReplyEvidenceIds\":[],\"selections\":["
                                + "{\"bggId\":501,\"whyFit\":\"节奏轻快，适合两人控制在一小时内完成。\","
                                + "\"tradeoff\":\"策略纵深相对温和。\",\"internalEvidenceIds\":[\"B501:playerCount\",\"B501:durationMinutes\"]},"
                                + "{\"bggId\":502,\"whyFit\":\"保留两人适配与时长边界，同时提供更高的策略密度。\","
                                + "\"internalEvidenceIds\":[\"B502:playerCount\",\"B502:durationMinutes\",\"B502:complexity\"],"
                                + "\"presentationHint\":\"compact\"}],\"clientTrace\":\"additive-field\"}"));
        RecommendationReActLoop loop = loop(model, catalog);
        RecommendationProfile pollutedLegacyProfile = new RecommendationProfile(
                null, null, null, BggGameType.EXPANSION, InteractionPreference.ANY);

        var response = loop.converse(
                new ConversationRequest(
                        pollutedLegacyProfile,
                        "找两款标题包含 Grove、适合两人、一小时内且复杂度 1 到 3 的游戏，不要扩展。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.profile().playerCount()).satisfies(range -> {
            assertThat(range.minimum()).isEqualTo(2);
            assertThat(range.maximum()).isEqualTo(2);
            assertThat(range.strength()).isEqualTo(ConstraintRange.Strength.HARD);
        });
        assertThat(response.profile().durationMinutes()).satisfies(range -> {
            assertThat(range.minimum()).isNull();
            assertThat(range.maximum()).isEqualTo(60);
            assertThat(range.strength()).isEqualTo(ConstraintRange.Strength.HARD);
        });
        assertThat(response.profile().complexity()).satisfies(range -> {
            assertThat(range.minimum()).isEqualByComparingTo("1");
            assertThat(range.maximum()).isEqualByComparingTo("3");
            assertThat(range.strength()).isEqualTo(ConstraintRange.Strength.HARD);
        });
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(501, 502);
        assertThat(response.games()).extracting(game -> game.replyParts().getFirst().claim().text())
                .containsExactly(
                        "节奏轻快，适合两人控制在一小时内完成。",
                        "保留两人适配与时长边界，同时提供更高的策略密度。");
        assertThat(response.games()).allSatisfy(game -> assertThat(game.claims())
                .filteredOn(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                .extracting(CandidateClaim::subject)
                .containsExactly("playerCount", "durationMinutes", "complexity"));
        assertThat(response.games().getFirst().replyParts())
                .extracting(part -> part.claim().text())
                .containsExactly("节奏轻快，适合两人控制在一小时内完成。", "策略纵深相对温和。");
        assertThat(response.shortfall()).isNull();
        assertThat(catalog.searches).hasValue(1);
        assertThat(catalog.lastFilters.get().types()).isEmpty();
        assertThat(catalog.lastFilters.get().textQuery()).isEqualTo("Grove");

        Request terminalDecision = model.requests.getLast();
        String terminalContext = terminalDecision.messages().stream()
                .map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(terminalContext)
                .containsOnlyOnce("Verified fixture description for Grove Routes.")
                .containsOnlyOnce("Verified fixture description for Grove Lines.")
                .doesNotContain("Verified fixture description for Grove Routes: New Paths.");
        JsonNode searchObservation = toolObservation(terminalDecision, "search");
        assertThat(searchObservation.path("verifiedCandidateBggIds").toString()).isEqualTo("[501,502]");
        assertThat(searchObservation.path("canTerminateNow").asBoolean()).isTrue();
        assertThat(searchObservation.path("terminalAction").path("name").asText())
                .isEqualTo(BoardGameRecommendationAgent.RECOMMEND_TOOL);
        JsonNode comparisonObservation = toolObservation(terminalDecision, "compare");
        assertThat(comparisonObservation.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(comparisonObservation.path("code").asText()).isEqualTo("COMPARISON_OBSERVED");
        assertThat(comparisonObservation.path("axes")).hasSize(2);
        JsonNode publicationSchema = new ObjectMapper().readTree(terminalDecision.tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(publicationSchema.toString())
                .contains("whyFit", "Synthesize")
                .doesNotContain("cardText");
        assertThat(model.requests).flatExtracting(Request::tools)
                .extracting(tool -> tool.inputSchema())
                .noneMatch(schema -> schema.contains("additionalProperties"));
        loop.stopBoundedCalls();
    }

    @Test
    void singleIncludedTypeBecomesThePublishedProfileAndATypeFitClaim() throws Exception {
        Game socialSignal = game(551, "Social Signal", BggGameType.PARTY, 3, 8, 75, "2.1");
        Game quietEngine = game(552, "Quiet Engine", BggGameType.STRATEGY, 2, 5, 80, "2.2");
        RecordingCatalog catalog = new RecordingCatalog(socialSignal, quietEngine);
        ScriptedModel model = new ScriptedModel(
                action(
                        "typed-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"includeTypes\":[\"PARTY\"],\"excludeTypes\":[],"
                                + "\"players\":5,\"maxMinutes\":90,\"complexity\":{\"maximum\":2.5}}"),
                action(
                        "typed-publish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"这款符合本轮全部硬条件。\","
                                + "\"playerReplyEvidenceIds\":[],\"selections\":[{\"bggId\":551,"
                                + "\"whyFit\":\"五人、九十分钟内的轻量聚会选择。\","
                                + "\"internalEvidenceIds\":[\"B551:playerCount\",\"B551:durationMinutes\","
                                + "\"B551:complexity\",\"B551:bggType\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                null, null, null, BggGameType.EXPANSION, InteractionPreference.ANY),
                        "推荐一款适合五人、九十分钟内、复杂度不超过 2.5 的聚会游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.PARTY);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(551);
        assertThat(response.games().getFirst().claims())
                .filteredOn(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                .allSatisfy(claim -> {
                    assertThat(claim.strength()).isEqualTo(ConstraintRange.Strength.HARD);
                    assertThat(claim.relation()).isEqualTo(CandidateClaim.Relation.SATISFIED);
                })
                .extracting(CandidateClaim::subject)
                .containsExactly("playerCount", "durationMinutes", "complexity", "bggType");
        assertThat(toolObservation(model.requests.getLast(), "typed-search")
                        .path("verifiedCandidateBggIds").toString())
                .isEqualTo("[551]");
        loop.stopBoundedCalls();
    }

    @Test
    void laterResearchInvalidatesAnEarlierComparisonSnapshot() throws Exception {
        Game harborSignals = game(561, "Harbor Signals", BggGameType.FAMILY, 2, 4, 55, "2.0");
        Game harborCouncil = game(562, "Harbor Council", BggGameType.STRATEGY, 2, 4, 70, "2.5");
        RecordingCatalog catalog = new RecordingCatalog(harborSignals, harborCouncil);
        ScriptedModel model = new ScriptedModel(
                action(
                        "research-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"includeTypes\":[],\"excludeTypes\":[]}"),
                action(
                        "research-compare",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[561,562],\"subjects\":[\"mechanics\"]}"),
                action(
                        "research-after-compare",
                        BoardGameRecommendationAgent.RESEARCH_TOOL,
                        "{\"bggIds\":[561,562],\"question\":\"How does each game work for mixed-experience groups?\"}"),
                answer("新的玩家体验观察已经取代了先前只基于目录事实的比较。"));
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.of(new Research(
                        request.candidates().stream()
                                .map(candidate -> new BoardGameRecommendationWebResearch.GameResearch(
                                        candidate.bggId(),
                                        List.of(new BoardGameRecommendationWebResearch.Observation(
                                                "Reported experience for the verified candidate.", List.of(1)))))
                                .toList(),
                        List.of(new BoardGameRecommendationWebResearch.Source(
                                1,
                                "Independent play report",
                                "https://example.test/play-report",
                                "example.test"))));
            }
        };
        RecommendationReActLoop loop = loop(model, catalog, research);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "比较两款 Harbor 游戏；如果目录事实不够，再查看混合经验玩家的实际体验。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.comparison()).isNull();
        assertThat(toolObservation(model.requests.getLast(), "research-after-compare")
                        .path("status")
                        .asText())
                .isEqualTo("SUCCESS");
        loop.stopBoundedCalls();
    }

    @Test
    void conflictingTypePolarityAndCrossGameEvidenceAreRejectedAtTheirOwningBoundaries() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(601, "Orchard Assembly", BggGameType.FAMILY, 2, 4, 40, "1.7"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "conflict",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"includeTypes\":[\"FAMILY\"],"
                                + "\"excludeTypes\":[\"FAMILY\"]}"),
                answer("这个结构化条件同时包含并排除了 FAMILY；我需要你确认保留哪一边。"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款家庭游戏，但不要家庭游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(catalog.searches).hasValue(0);
        assertThat(toolObservation(model.requests.getLast(), "conflict").path("code").asText())
                .isEqualTo("SEARCH_TYPE_CONFLICT");
        assertThat(model.requests.getFirst().tools()).extracting(tool -> tool.name())
                .containsExactly(BoardGameRecommendationAgent.SEARCH_TOOL);
        JsonNode searchSchema = new ObjectMapper().readTree(model.requests.getFirst().tools().getFirst().inputSchema());
        assertThat(searchSchema.path("properties").has("includeTypes")).isTrue();
        assertThat(searchSchema.path("properties").has("excludeTypes")).isTrue();
        assertThat(searchSchema.toString())
                .doesNotContain("preferenceUpdates", "titleConstraint", "types\"", "browse_bgg_catalog");
        loop.stopBoundedCalls();

        RecordingCatalog evidenceCatalog = new RecordingCatalog(
                game(701, "Orchard Assembly", BggGameType.FAMILY, 2, 4, 40, "1.7"),
                game(702, "Meadow Signals", BggGameType.STRATEGY, 2, 4, 55, "2.2"));
        ScriptedModel evidenceModel = new ScriptedModel(
                action(
                        "evidence-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"includeTypes\":[],\"excludeTypes\":[]}"),
                action(
                        "foreign-evidence",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"推荐第一款。\","
                                + "\"playerReplyEvidenceIds\":[],\"selections\":[{\"bggId\":701,"
                                + "\"whyFit\":\"这张卡错误地借用了另一款游戏的证据。\","
                                + "\"internalEvidenceIds\":[\"B702:playerCount\"]}]}"),
                answer("我没有发布那张证据归属错误的卡片。"));
        RecommendationReActLoop evidenceLoop = loop(evidenceModel, evidenceCatalog);

        var evidenceResponse = evidenceLoop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "从这两款里推荐一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(evidenceResponse.outcome()).isEqualTo(Outcome.CONVERSATION);
        JsonNode rejection = toolObservation(evidenceModel.requests.getLast(), "foreign-evidence");
        assertThat(rejection.path("code").asText()).isEqualTo("RECOMMENDATION_EVIDENCE_NOT_GROUNDED");
        assertThat(rejection.path("errorPath").asText())
                .isEqualTo("$.selections[0].internalEvidenceIds[0]");
        assertThat(rejection.path("allowedCandidateBggIds").toString())
                .isEqualTo("[701,702]");
        assertThat(rejection.path("allowedEvidenceIdsByBggId").path("701").toString())
                .contains("B701:playerCount")
                .doesNotContain("B702:playerCount");
        assertThat(rejection.path("replacementInputSchema").path("required").toString())
                .contains("requestedCount", "selections");
        evidenceLoop.stopBoundedCalls();
    }

    private static RecommendationReActLoop loop(BoardGameRecommendationModel model, RecordingCatalog catalog) {
        BoardGameRecommendationWebResearch noResearch = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }
        };
        return loop(model, catalog, noResearch);
    }

    private static RecommendationReActLoop loop(
            BoardGameRecommendationModel model,
            RecordingCatalog catalog,
            BoardGameRecommendationWebResearch research) {
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        return new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper(),
                ObservationRegistry.NOOP);
    }

    private static Turn action(String id, String name, String arguments) {
        return new Turn("", List.of(new ToolCall(id, name, arguments)), CompletionStatus.COMPLETE);
    }

    private static Turn answer(String text) {
        return new Turn(text, List.of(), CompletionStatus.COMPLETE);
    }

    private static JsonNode toolObservation(Request request, String toolCallId) throws Exception {
        Message message = request.messages().stream()
                .filter(candidate -> candidate.role() == BoardGameRecommendationModel.Role.TOOL)
                .filter(candidate -> toolCallId.equals(candidate.toolCallId()))
                .findFirst()
                .orElseThrow();
        return new ObjectMapper().readTree(message.content());
    }

    private static Game game(
            int bggId,
            String name,
            BggGameType type,
            int minPlayers,
            int maxPlayers,
            int maxMinutes,
            String complexity) {
        return new Game(
                new Ranking(
                        bggId,
                        name,
                        2024,
                        bggId,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        1_500,
                        List.of(type)),
                new Details(
                        name,
                        "",
                        "",
                        minPlayers,
                        maxPlayers,
                        maxMinutes,
                        new BigDecimal(complexity),
                        List.of("Contract Category"),
                        List.of("Contract Mechanic"),
                        Math.max(5, maxMinutes - 15),
                        maxMinutes,
                        10,
                        10,
                        Integer.toString(maxPlayers),
                        minPlayers + "-" + maxPlayers,
                        2,
                        100,
                        List.of(),
                        List.of("Cross Game Designer"),
                        List.of("Open Shelf"),
                        "Verified fixture description for " + name + ".",
                        ""));
    }

    private static final class ScriptedModel implements BoardGameRecommendationModel {
        private final ArrayDeque<Turn> turns;
        private final List<Request> requests = new ArrayList<>();

        private ScriptedModel(Turn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean configured(String ownerUsername) {
            return true;
        }

        @Override
        public Turn next(Request request) {
            requests.add(request);
            if (turns.isEmpty()) {
                throw new AssertionError("scripted recommendation turns exhausted after "
                        + requests.getLast().messages());
            }
            return turns.removeFirst();
        }

        @Override
        public Turn next(Request request, String ownerUsername) {
            return next(request);
        }
    }

    private static final class RecordingCatalog implements BoardGameRecommendationCatalog {
        private final List<Game> games;
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicReference<CatalogFilters> lastFilters = new AtomicReference<>();

        private RecordingCatalog(Game... games) {
            this.games = List.of(games);
        }

        @Override
        public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
        }

        @Override
        public CandidateSet searchGames(CatalogFilters filters) {
            searches.incrementAndGet();
            lastFilters.set(filters);
            return new CandidateSet(games.size(), games, true);
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            return games.stream().filter(game -> bggIds.contains(game.ranking().bggId())).toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }
    }
}
