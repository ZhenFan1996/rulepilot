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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
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
                new Turn(
                        "这段工具调用前言不属于玩家回复，也不应污染下一轮上下文。",
                        List.of(new ToolCall(
                                "search",
                                BoardGameRecommendationAgent.SEARCH_TOOL,
                                "{\"evidence\":\"U1\",\"requestedCount\":2,\"includeTypes\":[],\"excludeTypes\":[\"EXPANSION\"],"
                                        + "\"requiredTitle\":{\"match\":\"CONTAINS\",\"value\":\"Grove\"},"
                                        + "\"players\":2,\"maxMinutes\":60,\"complexity\":{\"minimum\":1,\"maximum\":3,\"unit\":\"BGG_WEIGHT\"},"
                                        + "\"clientTrace\":\"additive-field\"}")),
                        CompletionStatus.COMPLETE),
                action(
                        "publish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"这两款都符合本轮条件；前者更轻快，后者更偏策略。\","
                                + "\"selections\":["
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
                .doesNotContain(
                        "Verified fixture description for Grove Routes: New Paths.",
                        "这段工具调用前言不属于玩家回复");
        JsonNode searchObservation = toolObservation(terminalDecision, "search");
        assertThat(searchObservation.path("verifiedCandidateBggIds").toString()).isEqualTo("[501,502]");
        assertThat(searchObservation.path("canTerminateNow").asBoolean()).isTrue();
        assertThat(searchObservation.path("terminalAction").path("name").asText())
                .isEqualTo(BoardGameRecommendationAgent.RECOMMEND_TOOL);
        assertThat(terminalDecision.tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .doesNotContain(
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        BoardGameRecommendationAgent.COMPARE_TOOL);
        JsonNode publicationSchema = new ObjectMapper().readTree(terminalDecision.tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(publicationSchema.toString())
                .contains("whyFit", "Synthesize")
                .doesNotContain("cardText");
        assertThat(publicationSchema.path("properties").path("playerReply").path("maxLength").asInt())
                .isEqualTo(RecommendationPublication.PLAYER_REPLY_MAX_CODE_POINTS);
        assertThat(publicationSchema.path("properties")
                        .path("selections")
                        .path("items")
                        .path("properties")
                        .path("whyFit")
                        .path("maxLength")
                        .asInt())
                .isEqualTo(RecommendationPublication.WHY_FIT_MAX_CODE_POINTS);
        assertThat(publicationSchema.path("properties")
                        .path("selections")
                        .path("items")
                        .path("properties")
                        .path("bggId")
                        .path("enum")
                        .toString())
                .isEqualTo("[501,502]");
        assertThat(publicationSchema.toString()).doesNotContain("oneOf");
        assertThat(model.requests).flatExtracting(Request::tools)
                .extracting(tool -> tool.inputSchema())
                .noneMatch(schema -> schema.contains("additionalProperties"));
        assertThat(model.requests)
                .allSatisfy(request -> assertThat(request.maxOutputTokens()).isEqualTo(2000));
        loop.stopBoundedCalls();
    }

    @Test
    void typedDescriptionConceptsRankTheHardFilteredCatalogWithoutAddingAnotherRead() throws Exception {
        Game quietArchive = game(541, "Quiet Archive", BggGameType.FAMILY, 2, 4, 50, "2.0");
        Game stormShelter = game(542, "Storm Shelter", BggGameType.FAMILY, 2, 4, 55, "2.1");
        RecordingCatalog catalog = new RecordingCatalog(quietArchive, stormShelter);
        ScriptedModel model = new ScriptedModel(
                action(
                        "description-ranked-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":2,\"includeTypes\":[\"FAMILY\"],"
                                + "\"excludeTypes\":[],\"requiredInteraction\":\"ANY\","
                                + "\"descriptionQuery\":\"shelter secrets storm atmosphere\","
                                + "\"players\":3,\"maxMinutes\":60}"),
                action(
                        "publish-description-ranked",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"playerReply\":\"两款都满足三人和一小时的硬条件，简介证据提供了不同主题方向。\","
                                + "\"selections\":["
                                + "{\"bggId\":541,\"whyFit\":\"偏向安静的档案主题。\","
                                + "\"internalEvidenceIds\":[\"B541:publisherDescription\"]},"
                                + "{\"bggId\":542,\"whyFit\":\"偏向暴风雨中的庇护所主题。\","
                                + "\"internalEvidenceIds\":[\"B542:publisherDescription\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "想找两款三人、一小时内，带有暴雨、庇护所和秘密氛围的家庭游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(catalog.lastFilters.get()).satisfies(filters -> {
            assertThat(filters.types()).containsExactly(BggGameType.FAMILY);
            assertThat(filters.textQuery()).isEqualTo("shelter secrets storm atmosphere");
            assertThat(filters.sort()).isEqualTo(BoardGameRecommendationCatalog.CatalogSort.RELEVANCE);
        });
        JsonNode searchObservation = toolObservation(model.requests.getLast(), "description-ranked-search");
        assertThat(searchObservation.path("appliedSearchContract").path("descriptionQuery").asText())
                .isEqualTo("shelter secrets storm atmosphere");
        String terminalContext = model.requests.getLast().messages().stream()
                .map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(terminalContext)
                .contains("Verified fixture description for Quiet Archive.")
                .contains("Verified fixture description for Storm Shelter.");
        assertThat(model.requests.getFirst().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .inputSchema())
                .contains("descriptionQuery");
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
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"PARTY\"],\"excludeTypes\":[],"
                                + "\"players\":5,\"maxMinutes\":90,\"complexity\":{\"maximum\":2.5}}"),
                action(
                        "typed-publish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"这款符合本轮全部硬条件。\","
                                + "\"selections\":[{\"bggId\":551,"
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
        assertThat(catalog.lastFilters.get()).satisfies(filters -> {
            assertThat(filters.textQuery()).isNull();
            assertThat(filters.sort()).isEqualTo(BoardGameRecommendationCatalog.CatalogSort.RANK);
        });
        loop.stopBoundedCalls();
    }

    @Test
    void oneBatchedResearchReadCompletesTheCurrentRecommendation() throws Exception {
        Game harborSignals = game(561, "Harbor Signals", BggGameType.FAMILY, 2, 4, 55, "2.0");
        Game harborCouncil = game(562, "Harbor Council", BggGameType.STRATEGY, 2, 4, 70, "2.5");
        RecordingCatalog catalog = new RecordingCatalog(harborSignals, harborCouncil);
        ScriptedModel model = new ScriptedModel(
                action(
                        "research-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":2,\"includeTypes\":[],\"excludeTypes\":[],"
                                + "\"experienceQuestion\":\"How does each game work for mixed-experience groups?\"}"),
                action(
                        "publish-after-research",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"这两款都符合目录条件；玩家体验资料补充了取舍。\","
                                + "\"selections\":["
                                + "{\"bggId\":561,\"whyFit\":\"适合希望时间更短的一组。\","
                                + "\"internalEvidenceIds\":[\"B561:durationMinutes\"]},"
                                + "{\"bggId\":562,\"whyFit\":\"适合希望策略更重的一组。\","
                                + "\"internalEvidenceIds\":[\"B562:complexity\"]}]}"));
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

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(toolObservation(model.requests.getLast(), "research-search")
                        .path("experienceResearch")
                        .path("status")
                        .asText())
                .isEqualTo("SUCCESS");
        assertThat(model.requests.getFirst().toolChoice())
                .isEqualTo(BoardGameRecommendationModel.ToolChoice.AUTO);
        assertThat(model.requests.getLast().toolChoice())
                .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        assertThat(model.requests.getLast().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(BoardGameRecommendationAgent.RECOMMEND_TOOL)
                .doesNotContain(
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        BoardGameRecommendationAgent.RESEARCH_TOOL);
        loop.stopBoundedCalls();
    }

    @Test
    void aResearchedFollowUpPublishesPreviouslyVerifiedCandidatesWithoutAnUnavailableAction() {
        Game ironOrchard = game(571, "Iron Orchard", BggGameType.STRATEGY, 2, 4, 135, "3.8", "Worker Placement");
        Game stoneLedger = game(572, "Stone Ledger", BggGameType.STRATEGY, 2, 4, 115, "3.6", "Worker Placement");
        RecordingCatalog catalog = new RecordingCatalog(ironOrchard, stoneLedger);
        ScriptedModel model = new ScriptedModel(
                action(
                        "follow-up-research",
                        BoardGameRecommendationAgent.RESEARCH_TOOL,
                        "{\"bggIds\":[571,572],\"question\":\"Which has the better three-player experience?\"}"),
                action(
                        "follow-up-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"三人局我会优先第一款，第二款更短。\","
                                + "\"selections\":["
                                + "{\"bggId\":571,\"whyFit\":\"三人可玩，策略更重。\","
                                + "\"internalEvidenceIds\":[\"B571:playerCount\",\"B571:complexity\"]},"
                                + "{\"bggId\":572,\"whyFit\":\"三人可玩，时间更短。\","
                                + "\"internalEvidenceIds\":[\"B572:playerCount\",\"B572:durationMinutes\"]}]}"));
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
                                                "Reported three-player experience for this verified candidate.",
                                                List.of(1)))))
                                .toList(),
                        List.of(new BoardGameRecommendationWebResearch.Source(
                                1,
                                "Independent three-player report",
                                "https://example.test/three-player",
                                "example.test"))));
            }
        };
        RecommendationReActLoop loop = loop(model, catalog, research);
        ConversationRequest persistedFollowUp = new ConversationRequest(
                RecommendationProfile.empty(),
                "这两款里哪款三人体验最好？",
                List.of(),
                List.of(
                        new BoardGameRecommendationAgent.DialogueMessage("user", "我们三个人想玩工人放置重策。"),
                        new BoardGameRecommendationAgent.DialogueMessage("assistant", "先看这两款。"),
                        new BoardGameRecommendationAgent.DialogueMessage("user", "这两款里哪款三人体验最好？")),
                null,
                List.of(),
                List.of(571, 572),
                List.of(ironOrchard, stoneLedger));

        var response = loop.converseValidated(
                persistedFollowUp,
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(571, 572);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(model.requests.getFirst().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(
                        BoardGameRecommendationAgent.RESEARCH_TOOL,
                        BoardGameRecommendationAgent.RECOMMEND_TOOL)
                .doesNotContain(BoardGameRecommendationAgent.COMPARE_TOOL);
        assertThat(model.requests.getFirst().toolChoice())
                .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        assertThat(model.requests.getLast().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(BoardGameRecommendationAgent.RECOMMEND_TOOL)
                .doesNotContain(BoardGameRecommendationAgent.RESEARCH_TOOL);
        loop.stopBoundedCalls();
    }

    @Test
    void aShownCandidateFollowUpCannotBypassTerminalEvidenceWithFreeFormText() {
        Game first = game(576, "Shown Orchard", BggGameType.STRATEGY, 2, 4, 120, "3.8");
        Game second = game(577, "Shown Foundry", BggGameType.STRATEGY, 2, 4, 90, "3.4");
        ScriptedModel model = new ScriptedModel(answer(
                "第一款互动一定更强，第二款则完全没有卡位。"));
        RecommendationReActLoop loop = loop(model, new RecordingCatalog(first, second));
        ConversationRequest followUp = new ConversationRequest(
                RecommendationProfile.empty(),
                "这两款互动怎么取舍？",
                List.of(),
                List.of(
                        new BoardGameRecommendationAgent.DialogueMessage("user", "先看两款重策。"),
                        new BoardGameRecommendationAgent.DialogueMessage("assistant", "先看这两款。"),
                        new BoardGameRecommendationAgent.DialogueMessage("user", "这两款互动怎么取舍？")),
                null,
                List.of(),
                List.of(576, 577),
                List.of(first, second));

        var response = loop.converseValidated(
                followUp,
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage())
                .doesNotContain("互动一定更强", "完全没有卡位");
        assertThat(response.harness().failureDetailCode()).isEqualTo("PUBLICATION_MISSING");
        assertThat(model.requests.getFirst().toolChoice())
                .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        loop.stopBoundedCalls();
    }

    @Test
    void offlineFollowUpMayReshapeRestoredFactsOnceWithoutAnotherReadStage() {
        Game first = game(581, "Offline Orchard", BggGameType.STRATEGY, 2, 4, 120, "3.8");
        Game second = game(582, "Offline Foundry", BggGameType.STRATEGY, 2, 4, 90, "3.4");
        ScriptedModel model = new ScriptedModel(
                action(
                        "offline-comparison",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[581,582],\"subjects\":[\"durationMinutes\",\"complexity\"]}"),
                action(
                        "offline-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"离线时只能比较已核验的时长和复杂度；实际三人体验仍然未知。\","
                                + "\"selections\":["
                                + "{\"bggId\":581,\"whyFit\":\"复杂度较高。\",\"internalEvidenceIds\":[\"B581:complexity\"]},"
                                + "{\"bggId\":582,\"whyFit\":\"时间较短。\",\"internalEvidenceIds\":[\"B582:durationMinutes\"]}]}"));
        RecommendationReActLoop loop = loop(model, new RecordingCatalog(first, second));
        ConversationRequest followUp = new ConversationRequest(
                RecommendationProfile.empty(),
                "这两款三人玩怎么取舍？",
                List.of(),
                List.of(
                        new BoardGameRecommendationAgent.DialogueMessage("user", "先看两款重策。"),
                        new BoardGameRecommendationAgent.DialogueMessage("assistant", "先看这两款。"),
                        new BoardGameRecommendationAgent.DialogueMessage("user", "这两款三人玩怎么取舍？")),
                null,
                List.of(),
                List.of(581, 582),
                List.of(first, second));

        var response = loop.converseValidated(
                followUp,
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.comparison()).isNotNull();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(model.requests.getFirst().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(BoardGameRecommendationAgent.COMPARE_TOOL)
                .doesNotContain(BoardGameRecommendationAgent.RESEARCH_TOOL);
        assertThat(model.requests.getLast().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .doesNotContain(
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        BoardGameRecommendationAgent.RESEARCH_TOOL);
        assertThat(model.requests)
                .allSatisfy(request -> assertThat(request.toolChoice())
                        .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED));
        loop.stopBoundedCalls();
    }

    @Test
    void conflictingTypePolarityIsRejectedAtTheSearchBoundary() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(601, "Orchard Assembly", BggGameType.FAMILY, 2, 4, 40, "1.7"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "conflict",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"FAMILY\"],"
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
    }

    @Test
    void descriptionRankingCannotReplaceANamedTitleLookup() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(602, "Named Harbor", BggGameType.FAMILY, 2, 4, 40, "1.7"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "incompatible-description-query",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[],"
                                + "\"excludeTypes\":[],\"requiredInteraction\":\"ANY\","
                                + "\"requiredTitle\":{\"match\":\"EXACT\",\"value\":\"Named Harbor\"},"
                                + "\"descriptionQuery\":\"quiet harbor atmosphere\"}"),
                answer("我会保留点名游戏的身份边界，不用主题排序替代标题查找。"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "介绍一下 Named Harbor 的氛围。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(catalog.searches).hasValue(0);
        assertThat(toolObservation(model.requests.getLast(), "incompatible-description-query")
                        .path("code")
                        .asText())
                .isEqualTo("DESCRIPTION_QUERY_WITH_TITLE");
        loop.stopBoundedCalls();
    }

    @Test
    void invalidNarrativeEvidenceIsLocalizedWithoutRetryingTheVerifiedSelection() {
        RecordingCatalog evidenceCatalog = new RecordingCatalog(
                game(701, "Orchard Assembly", BggGameType.FAMILY, 2, 4, 40, "1.7"),
                game(702, "Meadow Signals", BggGameType.STRATEGY, 2, 4, 55, "2.2"));
        ScriptedModel evidenceModel = new ScriptedModel(
                action(
                        "evidence-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[],\"excludeTypes\":[]}"),
                action(
                        "foreign-evidence",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"推荐第一款。\","
                                + "\"selections\":[{\"bggId\":701,"
                                + "\"whyFit\":\"这张卡错误地借用了另一款游戏的证据。\","
                                + "\"internalEvidenceIds\":[\"B702:playerCount\"]}]}"));
        RecommendationReActLoop evidenceLoop = loop(evidenceModel, evidenceCatalog);

        var evidenceResponse = evidenceLoop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "从这两款里推荐一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(evidenceResponse.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(evidenceResponse.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(701);
        assertThat(evidenceResponse.games().getFirst().replyParts()).isEmpty();
        assertThat(evidenceResponse.assistantMessage()).isEqualTo("推荐第一款。");
        assertThat(evidenceResponse.assistantMessage())
                .doesNotContain("错误地借用了另一款游戏的证据");
        assertThat(evidenceResponse.harness().actions())
                .contains("RECOMMENDATION_NARRATIVE_PARTIAL", "RECOMMEND_GAMES");
        assertThat(evidenceResponse.harness().modelCalls()).isEqualTo(2);
        evidenceLoop.stopBoundedCalls();
    }

    @Test
    void aTerminalPublicationWithoutAnyVerifiedSelectionFailsWithoutARepairLoop() {
        RecordingCatalog catalog = new RecordingCatalog(
                game(801, "Harbor Lanterns", BggGameType.FAMILY, 2, 4, 45, "1.9"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "hard-boundary-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[],\"excludeTypes\":[]}"),
                action(
                        "hard-boundary-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"推荐未验证的候选。\","
                                + "\"selections\":[{\"bggId\":999,"
                                + "\"whyFit\":\"没有可验证依据。\","
                                + "\"internalEvidenceIds\":[\"B801:playerCount\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款家庭游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.games()).isEmpty();
        assertThat(response.candidatesEvaluated()).isEqualTo(1);
        assertThat(response.harness().failureReason())
                .isEqualTo(BoardGameRecommendationAgent.FailureReason.PUBLICATION_REJECTED);
        assertThat(response.harness().failureDetailCode()).isEqualTo("FINAL_ID_NOT_VERIFIED");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        loop.stopBoundedCalls();
    }

    @Test
    void aTerminalPublicationKeepsItsVerifiedSubsetWhenAnotherSelectionIsInvalid() {
        RecordingCatalog catalog = new RecordingCatalog(
                game(901, "Cedar Workshop", BggGameType.FAMILY, 2, 4, 35, "1.6"),
                game(902, "River Workshop", BggGameType.STRATEGY, 2, 4, 60, "2.4"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "mixed-boundary-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":2,\"includeTypes\":[],\"excludeTypes\":[]}"),
                action(
                        "mixed-boundary-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":2,\"playerReply\":\"两款都推荐。\","
                                + "\"selections\":["
                                + "{\"bggId\":999,\"whyFit\":\"未核验。\",\"internalEvidenceIds\":[\"B901:playerCount\"]},"
                                + "{\"bggId\":902,\"whyFit\":\"适合想玩稍重策略的玩家。\","
                                + "\"internalEvidenceIds\":[\"B902:complexity\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐两款游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(902);
        assertThat(response.assistantMessage())
                .contains("已经核验的候选")
                .doesNotContain("两款都推荐");
        assertThat(response.shortfall()).isEqualTo(new BoardGameRecommendationAgent.RecommendationShortfall(2, 1));
        assertThat(response.harness().fallbackUsed()).isTrue();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        loop.stopBoundedCalls();
    }

    @Test
    void configuredResultCountCapsCardsAndReportsAnExplicitLargerRequestAsAShortfall() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(951, "Cedar One", BggGameType.STRATEGY, 2, 4, 90, "3.1"),
                game(952, "Cedar Two", BggGameType.STRATEGY, 2, 4, 95, "3.2"),
                game(953, "Cedar Three", BggGameType.STRATEGY, 2, 4, 100, "3.3"),
                game(954, "Cedar Four", BggGameType.STRATEGY, 2, 4, 105, "3.4"),
                game(955, "Cedar Five", BggGameType.STRATEGY, 2, 4, 110, "3.5"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "bounded-result-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":5,\"includeTypes\":[\"STRATEGY\"],\"excludeTypes\":[]}"),
                action(
                        "bounded-result-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":5,\"playerReply\":\"先给你三款最有区分度的，目录里还有其他候选。\","
                                + "\"selections\":["
                                + "{\"bggId\":951,\"whyFit\":\"第一款策略最轻。\",\"internalEvidenceIds\":[\"B951:complexity\"]},"
                                + "{\"bggId\":952,\"whyFit\":\"第二款处于中间。\",\"internalEvidenceIds\":[\"B952:complexity\"]},"
                                + "{\"bggId\":953,\"whyFit\":\"第三款策略更重。\",\"internalEvidenceIds\":[\"B953:complexity\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我五款策略游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(951, 952, 953);
        assertThat(response.shortfall())
                .isEqualTo(new BoardGameRecommendationAgent.RecommendationShortfall(5, 3));
        assertThat(response.assistantMessage()).isEqualTo("先给你三款最有区分度的，目录里还有其他候选。");
        assertThat(response.harness().fallbackUsed()).isFalse();
        JsonNode searchObservation = toolObservation(model.requests.getLast(), "bounded-result-search");
        List<JsonNode> modelCandidates = new ArrayList<>();
        searchObservation.path("turnState").path("verifiedGames").forEach(modelCandidates::add);
        assertThat(modelCandidates).hasSize(5);
        assertThat(modelCandidates.subList(0, 3)).allSatisfy(candidate ->
                assertThat(candidate.path("observations").has(
                        "B" + candidate.path("bggId").asInt() + ":publisherDescription")).isTrue());
        assertThat(modelCandidates.subList(3, 5)).allSatisfy(candidate ->
                assertThat(candidate.path("observations").has(
                        "B" + candidate.path("bggId").asInt() + ":publisherDescription")).isFalse());
        JsonNode publicationSchema = new ObjectMapper().readTree(model.requests.getLast().tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(publicationSchema.path("properties")
                        .path("selections")
                        .path("maxItems")
                        .asInt())
                .isEqualTo(3);
        assertThat(publicationSchema.toString())
                .doesNotContain("B954:publisherDescription", "B955:publisherDescription");
        loop.stopBoundedCalls();
    }

    @Test
    void boundsTheUntrustedPublisherDescriptionInModelContextWithoutChangingCanonicalEvidence() throws Exception {
        String description = "narrative ".repeat(2_000);
        RecordingCatalog catalog = new RecordingCatalog(game(
                956,
                "Long Chronicle",
                BggGameType.STRATEGY,
                2,
                4,
                90,
                "3.2",
                List.of("Storytelling"),
                description));
        ScriptedModel model = new ScriptedModel(
                action(
                        "long-description-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"STRATEGY\"],\"excludeTypes\":[]}"),
                action(
                        "long-description-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"playerReply\":\"这款叙事策略游戏值得先看。\",\"selections\":[{\"bggId\":956,"
                                + "\"whyFit\":\"它有明确的叙事定位。\","
                                + "\"internalEvidenceIds\":[\"B956:publisherDescription\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款叙事策略游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        JsonNode observation = toolObservation(model.requests.getLast(), "long-description-search");
        String excerpt = observation.path("turnState")
                .path("verifiedGames")
                .get(0)
                .path("observations")
                .path("B956:publisherDescription")
                .get(1)
                .asText();
        assertThat(excerpt.codePointCount(0, excerpt.length()))
                .isLessThanOrEqualTo(RecommendationActions.MODEL_PUBLISHER_DESCRIPTION_MAX_CODE_POINTS);
        assertThat(excerpt).endsWith("…");
        assertThat(response.games().getFirst().replyParts().getFirst().claim().evidence().getFirst().value())
                .isEqualTo(description.strip());
        loop.stopBoundedCalls();
    }

    @Test
    void theTypedSearchCountCannotBeExpandedByTerminalPublication() throws Exception {
        Game previouslyShown = game(970, "Old Foundry", BggGameType.STRATEGY, 2, 4, 90, "3.0");
        RecordingCatalog catalog = new RecordingCatalog(
                game(971, "Cedar One", BggGameType.STRATEGY, 2, 4, 90, "3.1"),
                game(972, "Cedar Two", BggGameType.STRATEGY, 2, 4, 95, "3.2"),
                game(973, "Cedar Three", BggGameType.STRATEGY, 2, 4, 100, "3.3"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "one-result-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U2\",\"requestedCount\":1,\"includeTypes\":[\"STRATEGY\"],\"excludeTypes\":[]}"),
                action(
                        "expanded-terminal-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":3,\"playerReply\":\"这里擅自扩成了三款。\","
                                + "\"selections\":["
                                + "{\"bggId\":971,\"whyFit\":\"第一款。\",\"internalEvidenceIds\":[\"B971:complexity\"]},"
                                + "{\"bggId\":972,\"whyFit\":\"第二款。\",\"internalEvidenceIds\":[\"B972:complexity\"]},"
                                + "{\"bggId\":973,\"whyFit\":\"第三款。\",\"internalEvidenceIds\":[\"B973:complexity\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converseValidated(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "只换一款新的工人放置游戏。",
                        List.of(970),
                        List.of(
                                new DialogueMessage("user", "先推荐一款。"),
                                new DialogueMessage("assistant", "先看 Old Foundry。"),
                                new DialogueMessage("user", "只换一款新的工人放置游戏。")),
                        null,
                        List.of(new KnownGame(970, "Old Foundry", "Old Foundry")),
                        List.of(970),
                        List.of(previouslyShown)),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(971);
        assertThat(response.shortfall()).isNull();
        assertThat(response.assistantMessage()).doesNotContain("擅自扩成了三款");
        JsonNode observation = toolObservation(model.requests.getLast(), "one-result-search");
        assertThat(observation.path("appliedSearchContract").path("requestedCount").asInt())
                .isEqualTo(1);
        assertThat(observation.path("turnState").path("verifiedGames").findValuesAsText("bggId"))
                .containsExactly("971", "972", "973");
        JsonNode publicationSchema = new ObjectMapper().readTree(model.requests.getLast().tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(publicationSchema.path("properties").has("requestedCount")).isFalse();
        assertThat(publicationSchema.path("properties").path("selections").path("maxItems").asInt())
                .isEqualTo(1);
        loop.stopBoundedCalls();
    }

    @Test
    void oneNestedJsonArrayIsDecodedBeforeTheOrdinaryPublicationBoundary() {
        RecordingCatalog catalog = new RecordingCatalog(
                game(976, "Encoded Orchard", BggGameType.FAMILY, 3, 5, 45, "1.8"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "encoded-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"FAMILY\"],\"excludeTypes\":[]}"),
                action(
                        "encoded-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"playerReply\":\"这款适合轻松开局。\","
                                + "\"selections\":\"[{\\\"bggId\\\":976,\\\"whyFit\\\":\\\"三到五人都能玩。\\\","
                                + "\\\"internalEvidenceIds\\\":[\\\"B976:playerCount\\\"]}]\"}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我们一款轻松的家庭游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(976);
        assertThat(response.harness().actions())
                .contains("RECOMMENDATION_WIRE_FORMAT_NORMALIZED")
                .noneMatch(action -> action.startsWith("REJECTED_") || action.startsWith("PUBLICATION_FAILED:"));
        loop.stopBoundedCalls();
    }

    @Test
    void aNestedJsonObjectCannotMasqueradeAsTheSelectionArray() {
        RecordingCatalog catalog = new RecordingCatalog(
                game(977, "Encoded Decoy", BggGameType.FAMILY, 3, 5, 45, "1.8"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "decoy-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"FAMILY\"],\"excludeTypes\":[]}"),
                action(
                        "decoy-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"playerReply\":\"不能发布。\",\"selections\":\"{\\\"bggId\\\":977}\"}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我们一款轻松的家庭游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().actions())
                .contains("PUBLICATION_FAILED:PUBLICATION_SELECTION_COUNT_INVALID");
        loop.stopBoundedCalls();
    }

    @Test
    void unpublishedCheckpointCandidatesAreNotExposedToATypedFollowUp() {
        Game shown = game(981, "Shown Harbor", BggGameType.STRATEGY, 2, 4, 90, "3.1");
        Game unpublished = game(982, "Unpublished Harbor", BggGameType.STRATEGY, 2, 4, 95, "3.2");
        ScriptedModel model = new ScriptedModel(action(
                "shown-follow-up",
                BoardGameRecommendationAgent.RECOMMEND_TOOL,
                "{\"requestedCount\":1,\"playerReply\":\"我先只讨论已经展示的候选。\","
                        + "\"selections\":[{\"bggId\":981,\"whyFit\":\"适合二到四人。\","
                        + "\"internalEvidenceIds\":[\"B981:playerCount\"]}]}"));
        RecommendationReActLoop loop = loop(model, new RecordingCatalog(shown, unpublished));
        ConversationRequest followUp = new ConversationRequest(
                RecommendationProfile.empty(),
                "已经展示的那款怎么样？",
                List.of(),
                List.of(
                        new BoardGameRecommendationAgent.DialogueMessage("user", "先推荐一款。"),
                        new BoardGameRecommendationAgent.DialogueMessage("assistant", "先看 Shown Harbor。"),
                        new BoardGameRecommendationAgent.DialogueMessage("user", "已经展示的那款怎么样？")),
                null,
                List.of(
                        new BoardGameRecommendationAgent.KnownGame(981, "Shown Harbor", "Shown Harbor"),
                        new BoardGameRecommendationAgent.KnownGame(
                                982, "Unpublished Harbor", "Unpublished Harbor")),
                List.of(981),
                List.of(shown, unpublished));

        var response = loop.converseValidated(
                followUp,
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(981);
        assertThat(model.requests.getFirst().toolChoice())
                .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        String agentInput = model.requests.getFirst().messages().stream()
                .filter(message -> message.role() == BoardGameRecommendationModel.Role.USER)
                .map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(agentInput)
                .contains("Shown Harbor")
                .doesNotContain("Unpublished Harbor");
        loop.stopBoundedCalls();
    }

    @Test
    void oneCatalogContractEndsAsSafeNoMatchInsteadOfSearchingAgainOrGuessingTitles() {
        RecordingCatalog catalog = new RecordingCatalog();
        ScriptedModel model = new ScriptedModel(
                action(
                        "empty-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":3,\"includeTypes\":[\"FAMILY\"],\"excludeTypes\":[],"
                                + "\"players\":4,\"maxMinutes\":90}"),
                answer("目录里没有，但你们可以试试未经核验的 Ghost Harbor。"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚四个人聚会，不想学太复杂，也不想玩到半夜，有什么推荐？"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(response.assistantMessage())
                .contains("没有找到符合条件")
                .doesNotContain("Ghost Harbor");
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(catalog.searches).hasValue(1);
        assertThat(model.requests).hasSize(1);
        loop.stopBoundedCalls();
    }

    @Test
    void aWorkerPlacementPreferenceIsPartOfTheSingleTypedCatalogContract() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(1001, "Foundry Council", BggGameType.STRATEGY, 2, 4, 120, "3.7", "Worker Placement"),
                game(1002, "Ledger Duel", BggGameType.STRATEGY, 2, 4, 100, "3.5", "Auction/Bidding"));
        ScriptedModel model = new ScriptedModel(
                action(
                        "mechanic-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[\"STRATEGY\"],\"excludeTypes\":[],"
                                + "\"requiredMechanics\":[\"Worker Placement\"],\"players\":3,"
                                + "\"complexity\":{\"minimum\":3}}"),
                action(
                        "mechanic-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"这款符合你们想玩的方向。\","
                                + "\"selections\":[{\"bggId\":1001,"
                                + "\"whyFit\":\"三人可玩且属于工人放置机制。\","
                                + "\"internalEvidenceIds\":[\"B1001:playerCount\",\"B1001:mechanics\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们三个人想玩一些工人放置的德式重策。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(1001);
        assertThat(catalog.lastFilters.get().mechanics()).containsExactly("Worker Placement");
        JsonNode observation = toolObservation(model.requests.getLast(), "mechanic-search");
        assertThat(observation.path("appliedSearchContract").path("requiredMechanics").toString())
                .isEqualTo("[\"Worker Placement\"]");
        assertThat(model.requests.getFirst().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .inputSchema())
                .contains("requiredMechanics");
        loop.stopBoundedCalls();
    }

    @Test
    void anExplicitCooperativeModeIsAHardTypedCatalogConstraint() throws Exception {
        RecordingCatalog catalog = new RecordingCatalog(
                game(
                        1011,
                        "Rival Woodland",
                        BggGameType.STRATEGY,
                        2,
                        4,
                        75,
                        "2.8",
                        List.of("Area Majority / Influence")),
                game(
                        1012,
                        "Shared Flight",
                        BggGameType.STRATEGY,
                        2,
                        2,
                        45,
                        "2.1",
                        List.of("Cooperative Game", "Communication Limits")));
        ScriptedModel model = new ScriptedModel(
                action(
                        "cooperative-search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"evidence\":\"U1\",\"requestedCount\":1,\"includeTypes\":[],\"excludeTypes\":[],"
                                + "\"requiredInteraction\":\"COOPERATIVE\",\"players\":2,\"maxMinutes\":90}"),
                action(
                        "cooperative-publication",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"requestedCount\":1,\"playerReply\":\"这款是核验过的双人纯合作游戏。\","
                                + "\"selections\":[{\"bggId\":1012,"
                                + "\"whyFit\":\"两个人共同对抗系统，并能在九十分钟内结束。\","
                                + "\"internalEvidenceIds\":[\"B1012:playerCount\",\"B1012:durationMinutes\",\"B1012:mechanics\"]}]}"));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们两个人想玩纯合作、有点故事感、九十分钟以内的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.COOPERATIVE);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(1012);
        assertThat(response.games().getFirst().claims())
                .extracting(CandidateClaim::subject)
                .contains("interaction");
        assertThat(catalog.lastFilters.get().mechanics()).containsExactly("Cooperative Game");
        JsonNode observation = toolObservation(model.requests.getLast(), "cooperative-search");
        assertThat(observation.path("verifiedCandidateBggIds").toString()).isEqualTo("[1012]");
        assertThat(observation.path("appliedSearchContract").path("requiredInteraction").asText())
                .isEqualTo("COOPERATIVE");
        assertThat(model.requests.getFirst().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .inputSchema())
                .contains("requiredInteraction", "COOPERATIVE", "TEAM");
        loop.stopBoundedCalls();
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
        return game(
                bggId,
                name,
                type,
                minPlayers,
                maxPlayers,
                maxMinutes,
                complexity,
                "Contract Mechanic");
    }

    private static Game game(
            int bggId,
            String name,
            BggGameType type,
            int minPlayers,
            int maxPlayers,
            int maxMinutes,
            String complexity,
            String mechanic) {
        return game(
                bggId,
                name,
                type,
                minPlayers,
                maxPlayers,
                maxMinutes,
                complexity,
                List.of(mechanic));
    }

    private static Game game(
            int bggId,
            String name,
            BggGameType type,
            int minPlayers,
            int maxPlayers,
            int maxMinutes,
            String complexity,
            List<String> mechanics) {
        return game(
                bggId,
                name,
                type,
                minPlayers,
                maxPlayers,
                maxMinutes,
                complexity,
                mechanics,
                "Verified fixture description for " + name + ".");
    }

    private static Game game(
            int bggId,
            String name,
            BggGameType type,
            int minPlayers,
            int maxPlayers,
            int maxMinutes,
            String complexity,
            List<String> mechanics,
            String description) {
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
                        mechanics,
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
                        description,
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
