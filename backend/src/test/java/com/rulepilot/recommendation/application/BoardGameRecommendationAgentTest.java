package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.NaturalReply;
import com.rulepilot.recommendation.BoardGameRecommendationModel.NaturalReplyRequest;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressAction;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationAgentTest {

    @Test
    void resolvesAnExplicitlySelectedCurrentTargetWithoutAPlanningModelTurn() {
        Game target = game(
                71,
                "Harbor Nova",
                50,
                List.of("Strategy"),
                List.of("Open Drafting", "Set Collection"));
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(71, target),
                Map.of("星港（Harbor Nova）", 71));
        ScriptedModel model = new ScriptedModel(List.of());
        List<ProgressUpdate> progress = new ArrayList<>();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚已经决定玩星港（Harbor Nova），请直接找到这款并打开规则书。"),
                "zh-CN",
                progress::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_FAST_PATH);
        assertThat(response.games()).extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(71);
        assertThat(response.harness().modelCalls()).isZero();
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
        assertThat(catalog.lastResolvedTitle).isEqualTo("星港（Harbor Nova）");
        assertThat(progress)
                .extracting(ProgressUpdate::action)
                .contains(ProgressAction.RESOLVE_BGG_GAME)
                .doesNotContain(ProgressAction.CHOOSE_NEXT_ACTION);
    }

    @Test
    void leavesAComparisonBetweenTwoNamedGamesUnderTheConversationModel() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "compare-as-conversation",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"我会按你指定的两个对象比较，不会把其中一个误当成已选目标。\"}")));
        TrackingCatalog catalog = new TrackingCatalog();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "请比较《Harbor Nova》和《Loom City》，告诉我核心差别。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(catalog.calls).isZero();
    }

    @Test
    void leavesAnOpenQuestionAboutOneNamedGameUnderTheConversationModel() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "discuss-title",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"这是一个开放问题，需要先理解你想了解的资料范围。\"}")));
        TrackingCatalog catalog = new TrackingCatalog();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "《Harbor Nova》的美术是谁画的？"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(catalog.calls).isZero();
    }

    @Test
    void returnsAPlayerNamedTargetAsASelectableRecommendationCardInTheResolvingTurn() {
        Game target = game(
                50,
                "Mosaic Field",
                45,
                List.of("Abstract Strategy"),
                List.of("Pattern Building", "Tile Placement"));
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(50, target),
                Map.of("蓝瓷花园（Mosaic Field）", 50));
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
            assertThat(request.messages().getFirst().content()).contains(
                    "give the latest explicit request priority over older turns",
                    "A prose confirmation does not complete that request");
            String resolutionSchema = request.tools().stream()
                    .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                    .findFirst()
                    .orElseThrow()
                    .inputSchema();
            assertThat(resolutionSchema).contains(
                    "TARGET_GAME",
                    "COMPARISON_REFERENCE",
                    "DISCUSSION_SUBJECT",
                    "IDENTITY_ONLY");
            assertThat(schema(request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow())
                    .at("/properties/message")
                    .isMissingNode()).isTrue();
            return action(
                    "resolve-target",
                    BoardGameRecommendationAgent.RESOLVE_TOOL,
                    "{\"title\":\"蓝瓷花园\",\"purpose\":\"TARGET_GAME\"}");
        }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                5,
                                30,
                                new BigDecimal("1.5"),
                                BggGameType.PARTY,
                                InteractionPreference.COMPETITIVE),
                        "我想玩蓝瓷花园（Mosaic Field）",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(50)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(50);
        assertThat(response.assistantMessage())
                .isEqualTo("已核对你指定的这款游戏；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
        assertThat(catalog.calls).isEqualTo(1);
        assertThat(catalog.lastResolvedTitle).isEqualTo("蓝瓷花园（Mosaic Field）");
    }

    @Test
    void rejectsLegacyModelWrittenTargetMessageAndAcceptsIdentityOnly() {
        Game target = game(
                50,
                "Mosaic Field",
                45,
                List.of("Abstract Strategy"),
                List.of("Pattern Building", "Tile Placement"));
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(50, target),
                Map.of("Mosaic Field", 50));
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> unmodifiedAction(
                        "legacy-target-message",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Mosaic Field\",\"purpose\":\"TARGET_GAME\","
                                + "\"message\":\"A model-written promise that must never reach the player.\"}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("UNEXPECTED_ARGUMENT");
                    return action(
                            "identity-only-target",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"TARGET_GAME\"}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "I want to play Mosaic Field."),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("The game you named has been verified; its card shows only checkable data and attributed information.")
                .doesNotContain("model-written promise");
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:UNEXPECTED_ARGUMENT",
                "RESOLVE_BGG_REFERENCE",
                "RECOMMEND_GAMES");
    }

    @Test
    void givesTheAgentTheStoredPublisherDescriptionAsBoundedCandidateEvidence() {
        Game described = gameWithDescription(
                70,
                "Cloud Archive",
                "Players build a floating archive and preserve memories before each island disappears.");
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(70, described),
                Map.of("Cloud Archive", 70));
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-title",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Cloud Archive\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "B70:publisherDescription",
                                    "Players build a floating archive",
                                    "publisherDescription");
                    return action(
                            "recommend-described",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"如果你想要这个题材，我会先看它。\","
                                    + "\"selections\":[{\"bggId\":70,"
                                    + "\"why\":\"出版方描述的是在浮空档案馆中保存记忆。\","
                                    + "\"tradeoff\":\"这段简介不能证明实际桌感。\","
                                    + "\"internalEvidenceIds\":[\"B70:publisherDescription\"]}]}"
                    );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我想玩一个围绕保存记忆的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game -> assertThat(game.reasons())
                .anySatisfy(reason -> assertThat(reason.text())
                        .contains(
                                "发行方简介（宣传资料）",
                                "floating archive",
                                "preserve memories")));
    }

    @Test
    void doesNotRepublishAnExplicitlyExcludedGameWhenTheModelMisclassifiesItAsATarget() {
        Game target = game(
                50,
                "Mosaic Field",
                45,
                List.of("Abstract Strategy"),
                List.of("Pattern Building", "Tile Placement"));
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(50, target),
                Map.of("Mosaic Field", 50));
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "resolve-excluded",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Mosaic Field\",\"purpose\":\"TARGET_GAME\","
                                + "\"message\":\"I found the title.\"}"),
                request -> {
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.REPLY_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    return action(
                            "respect-exclusion",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"I will keep Mosaic Field excluded.\","
                                    + "\"referencedBggIds\":[50]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Do not recommend Mosaic Field.",
                        List.of(50),
                        List.of(),
                        null,
                        List.of(),
                        List.of()),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE", "REPLY_TO_USER");
    }

    @Test
    void letsTheModelChooseFiveResultsWhenTheConversationAsksForFive() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.maxOutputTokens()).isEqualTo(600);
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "honor an explicit result count",
                                    "Recommend only verified, hard-eligible IDs");
                    return action(
                            "inspect-six",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\",\"Paper Harbor\",\"Quiet Comet\",\"Copper Parade\"]}");
                },
                request -> {
                    assertThat(request.maxOutputTokens()).isEqualTo(1_200);
                    var recommendation = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    JsonNode selections = schema(recommendation).at("/properties/selections");
                    assertThat(selections.path("minItems").intValue()).isEqualTo(5);
                    assertThat(selections.path("maxItems").intValue()).isEqualTo(5);
                    assertThat(request.messages().get(1).content())
                            .contains("\"explicitRecommendationCount\":5");
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"recommendableBggIds\":[60,61,63,64,65,66]",
                                    "\"B60:designers\":[\"M\",\"Designer A\"]",
                                    "\"B60:publishers\":[\"M\",\"Publisher A\"]");
                    return action(
                            "recommend-five",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你要的数量给五个不同方向，先看卡片再慢慢缩小范围。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61},{\"bggId\":63},{\"bggId\":64},{\"bggId\":65}]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "别只给三款，我现在想先看五个不同方向。"),
                "zh-CN",
                null,
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61, 63, 64, 65);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME", "LOOKUP_BGG_CANDIDATES", "RECOMMEND_GAMES");
    }

    @Test
    void dropsAnInvalidOptionalTypeHintWithoutDiscardingAUsefulCatalogRead() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    ToolSpec browse = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(browse.description())
                            .contains("interaction modes", "never types", "Omit types");
                    return action(
                            "browse-with-one-category-and-one-interaction",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            "{\"types\":[\"STRATEGY\",\"COOPERATIVE\"],\"limit\":5}");
                },
                request -> action(
                        "recommend-five-after-tolerant-browse",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        recommendationArgumentsFromCurrentSchema(request))));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "别再问了，直接给我五款不同方向。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).hasSize(5);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "DROPPED_OPTIONAL_GAME_TYPE_HINT",
                "SEARCH_BGG_CATALOG",
                "RECOMMEND_GAMES");
    }

    @Test
    void explicitResultCountCannotEndAsPlainTextAfterCandidatesWereVerified() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-five",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":5}"),
                ignored -> action(
                        "plain-text-instead-of-cards",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"我先讲五种方向，你再选。\"}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("REPLY_RECOMMENDATION_REQUIRES_CARDS", "recommend_games");
                    return action(
                            "correct-to-five-cards",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            recommendationArgumentsFromCurrentSchema(request));
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "不用问，直接给我五款。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).hasSize(5);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_CATALOG",
                "REJECTED_ACTION:REPLY_RECOMMENDATION_REQUIRES_CARDS",
                "RECOMMEND_GAMES");
    }

    @Test
    void excludesPreviouslyShownGamesFromTheModelChoiceSetWithoutParsingRefreshPhrases() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"previouslyShownBggIds\":[60,61]",
                                    "\"recommendableBggIds\":[63]");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[63]")
                            .doesNotContain("\"enum\":[60, 61, 63]");
                    return action(
                            "try-old-card",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我再整理一下。\",\"selections\":[{\"bggId\":60}]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("FINAL_ID_PREVIOUSLY_SHOWN");
                    return action(
                            "recommend-unseen",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"前面的候选保留在上下文里，这次只补一个尚未展示的新方向。\","
                                    + "\"selections\":[{\"bggId\":63}]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "前面那些先搁着，随便再抛点我没见过的。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(60, 61)),
                "zh-CN",
                null,
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(63);
        assertThat(response.harness().actions()).contains(
                "REJECTED_ACTION:FINAL_ID_PREVIOUSLY_SHOWN", "RECOMMEND_GAMES");
    }

    @Test
    void overfetchesBroadCandidatesToReplacePreviouslyShownGamesBeforeApplyingTheRequestedCount() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-unresolved-hypotheses",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Unmapped One\",\"Unmapped Two\",\"Unmapped Three\"]}"),
                ignored -> action(
                        "browse-for-three-new-results",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3}"),
                request -> {
                    assertThat(catalog.maximumRequested).isEqualTo(6);
                    assertThat(request.messages().getLast().content())
                            .contains("\"previouslyShownBggIds\":[60,61,63]")
                            .contains("64", "65", "66")
                            .doesNotContain("\"recommendableBggIds\":[60");
                    return action(
                            "recommend-three-unseen",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"这次补上三个尚未展示的新方向。\","
                                    + "\"selections\":[{\"bggId\":64},{\"bggId\":65},{\"bggId\":66}]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "这些先放一边，再给我三个没看过的。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(60, 61, 63)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(64, 65, 66);
    }

    @Test
    void floorsTheModelBrowseLimitAtTheExplicitCountBeforeApplyingHardGates() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-with-hard-duration",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1}"),
                request -> {
                    assertThat(catalog.maximumRequested)
                            .as("the catalog page must leave room for candidates rejected by deterministic hard gates")
                            .isEqualTo(6);
                    assertThat(request.messages().getLast().content())
                            .contains("64", "65", "66")
                            .doesNotContain("\"recommendableBggIds\":[60");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema())
                            .contains("\"minItems\":3,\"maxItems\":3");
                    return action(
                            "recommend-three-after-hard-filter",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"这三款都压在五十分钟内，卡片里保留各自能核对的差异。\","
                                    + "\"selections\":[{\"bggId\":64},{\"bggId\":65},{\"bggId\":66}]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                null,
                                50,
                                null,
                                BggGameType.ALL,
                                InteractionPreference.ANY),
                        "给我三款五十分钟以内的桌游"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(64, 65, 66);
    }

    @Test
    void publishesATypedNaturalShortfallOnceWhenOnlyTwoHardEligibleCandidatesCanSatisfyARequestForThree() {
        TrackingCatalog catalog = catalogWithTwoShortGames();
        String rawMessage = "按当前九十分钟上限，目录里只有两款候选通过了硬条件，所以这次先把两款都给你，不拿重复游戏凑第三款。若想继续扩大范围，可以直接选择放宽时长。";
        String rawRelaxation = "可以把时长上限放宽到 120 分钟";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-two-hard-eligible",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3}"),
                request -> {
                    ToolSpec recommendation = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(recommendation.inputSchema()).contains(
                            "\"minItems\":2,\"maxItems\":2",
                            "\"shortfall\"",
                            "\"requestedCount\":{\"type\":\"integer\",\"enum\":[3]}",
                            "\"availableCount\":{\"type\":\"integer\",\"enum\":[2]}",
                            "\"subject\":{\"type\":\"string\",\"enum\":[\"durationMinutes\"]}",
                                    "\"required\":[\"selections\",\"shortfall\"]");
                    return action(
                            "publish-two-with-typed-shortfall",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"" + rawMessage + "\",\"selections\":["
                                    + "{\"bggId\":60,"
                                    + "\"why\":\"Glass Orchard 标注 40..55 分钟，落在九十分钟上限内。\","
                                    + "\"tradeoff\":\"当前资料不能证明新手是否容易上手。\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                                    + "{\"bggId\":61,"
                                    + "\"why\":\"Loom City 标注 45..60 分钟，也落在九十分钟上限内。\","
                                    + "\"tradeoff\":\"当前资料不能证明谈条件时的实际桌感。\","
                                    + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]}],"
                                    + "\"shortfall\":{\"requestedCount\":3,\"availableCount\":2,"
                                    + "\"relaxationOptions\":[{\"subject\":\"durationMinutes\","
                                    + "\"reply\":\"" + rawRelaxation + "\"}]}}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                null,
                                90,
                                null,
                                BggGameType.ALL,
                                InteractionPreference.ANY),
                        "最多九十分钟，请直接给我三款。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("当前硬条件下只核对到 2 款，少于你要的 3 款；先展示这些，不会重复凑数。");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.shortfall()).isNotNull();
        assertThat(response.shortfall().requestedCount()).isEqualTo(3);
        assertThat(response.shortfall().availableCount()).isEqualTo(2);
        assertThat(response.clarification().prompt())
                .isEqualTo("当前硬条件下只核对到 2 款，少于你要的 3 款；先展示这些，不会重复凑数。");
        assertThat(response.clarification().options()).singleElement().satisfies(option -> {
            assertThat(option.value()).isEqualTo(rawRelaxation);
            assertThat(option.label()).isEqualTo(rawRelaxation);
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_CATALOG",
                "RECOMMENDATION_AVAILABILITY_SHORTFALL",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsMalformedShortfallRelaxationAndLetsTheAgentCorrectTheDecision() {
        TrackingCatalog catalog = catalogWithTwoShortGames();
        String rawMessage = "当前硬条件下只有两款可用；我先把这两款完整给你，不用重复项补数。";
        String firstWhy = "Glass Orchard 标注 40..55 分钟，符合当前时长上限。";
        String secondWhy = "Loom City 标注 45..60 分钟，也符合当前时长上限。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-two-hard-eligible",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3}"),
                ignored -> action(
                        "publish-shortfall-with-bad-optional-button",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\",\"selections\":["
                                + "{\"bggId\":60,"
                                + "\"why\":\"" + firstWhy + "\","
                                + "\"tradeoff\":\"当前资料没有玩家体验报告。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                                + "{\"bggId\":61,"
                                + "\"why\":\"" + secondWhy + "\","
                                + "\"tradeoff\":\"当前资料没有玩家体验报告。\","
                                + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]}],"
                                + "\"shortfall\":{\"requestedCount\":3,\"availableCount\":2,"
                                + "\"relaxationOptions\":[{\"subject\":\"notAProfileField\","
                                + "\"reply\":\"换个方向\"}]}}"),
                ignored -> action(
                        "correct-shortfall-relaxation",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\",\"selections\":["
                                + "{\"bggId\":60,"
                                + "\"why\":\"" + firstWhy + "\","
                                + "\"tradeoff\":\"当前资料没有玩家体验报告。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                                + "{\"bggId\":61,"
                                + "\"why\":\"" + secondWhy + "\","
                                + "\"tradeoff\":\"当前资料没有玩家体验报告。\","
                                + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]}],"
                                + "\"shortfall\":{\"requestedCount\":3,\"availableCount\":2,"
                                + "\"relaxationOptions\":[{\"subject\":\"durationMinutes\","
                                + "\"reply\":\"可以放宽到 120 分钟\"}]}}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                null,
                                90,
                                null,
                                BggGameType.ALL,
                                InteractionPreference.ANY),
                        "最多九十分钟，请直接给我三款。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("当前硬条件下只核对到 2 款，少于你要的 3 款；先展示这些，不会重复凑数。");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.games()).extracting(game -> game.reasons().getFirst().text())
                .containsExactly(
                        "BGG 标注最长约 55 分钟，在你的上限内",
                        "BGG 标注最长约 60 分钟，在你的上限内");
        assertThat(response.shortfall()).isNotNull();
        assertThat(response.shortfall().requestedCount()).isEqualTo(3);
        assertThat(response.shortfall().availableCount()).isEqualTo(2);
        assertThat(response.clarification()).isNotNull();
        assertThat(response.clarification().options()).singleElement().satisfies(option ->
                assertThat(option.value()).isEqualTo("可以放宽到 120 分钟"));
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_CATALOG",
                "REJECTED_ACTION:SHORTFALL_RELAXATION_INVALID",
                "RECOMMENDATION_AVAILABILITY_SHORTFALL",
                "RECOMMEND_GAMES");
    }

    @Test
    void chatsNaturallyWhenEmptyReferencesAreOmittedWithoutForcingARepairTurn() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools()).extracting(tool -> tool.name())
                    .contains(
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            BoardGameRecommendationAgent.ASK_TOOL)
                    .doesNotContain(BoardGameRecommendationAgent.RECOMMEND_TOOL);
            assertThat(request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.REPLY_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema())
                    .contains("\"required\":[\"message\"]");
            assertThat(request.messages().get(1).content()).contains("最近总是玩重策，今天只想聊聊桌游设计");
            return action(
                    "reply",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"当然可以。最近哪种设计最让你念念不忘？我们可以先随便聊，不急着挑游戏。\"}");
        }));
        TrackingCatalog catalog = new TrackingCatalog();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "最近总是玩重策，今天只想聊聊桌游设计"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).contains("先随便聊");
        assertThat(response.clarification()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(catalog.calls).isZero();
    }

    @Test
    void answersAnObviousControlTurnLocallyWithoutRestoringCardsOrCallingAModel() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of());
        List<String> streamed = new ArrayList<>();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "谢谢，先不用再推荐了",
                        List.of(),
                        List.of(new DialogueMessage("user", "谢谢，先不用再推荐了")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN",
                null,
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.games()).isEmpty();
        assertThat(response.assistantMessage()).contains("先停在这里", "当前对话接上");
        assertThat(response.mode()).isEqualTo(DecisionMode.LOCAL_FAST_PATH);
        assertThat(response.harness().modelCalls()).isZero();
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("DIRECT_REPLY_FAST_PATH:PAUSE");
        assertThat(streamed).containsExactly(response.assistantMessage());
        assertThat(model.naturalReplyCalls).hasValue(0);
    }

    @Test
    void handlesWholeMessageSocialAndControlTurnsLocallyWithoutToolsOrProviderLatency() {
        ScriptedModel model = new ScriptedModel(List.of());
        TrackingCatalog catalog = new TrackingCatalog();

        List<String> messages = List.of("你好！", "Thanks!", "what can you do?", "先暂停。");
        List<String> actions = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        for (String message : messages) {
            var response = agent(model, catalog, noResearch()).converse(
                    new ConversationRequest(RecommendationProfile.empty(), message),
                    message.codePoints().anyMatch(value -> value > 127) ? "zh-CN" : "en");
            assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(response.mode()).isEqualTo(DecisionMode.LOCAL_FAST_PATH);
            assertThat(response.harness().modelCalls()).isZero();
            assertThat(response.assistantMessage()).isNotBlank();
            actions.add(response.harness().actions().getFirst());
            replies.add(response.assistantMessage());
        }

        assertThat(actions).containsExactly(
                "DIRECT_REPLY_FAST_PATH:GREETING",
                "DIRECT_REPLY_FAST_PATH:THANKS",
                "DIRECT_REPLY_FAST_PATH:CAPABILITY",
                "DIRECT_REPLY_FAST_PATH:PAUSE");
        assertThat(replies).containsExactly(
                "你好。想找新桌游、比较候选，还是继续看某款游戏的规则？",
                "You're welcome. When you want to continue, tell me what to find, compare, or explain next.",
                "I can find games by group, time, and preferences; verify BGG data and attributed play reports; compare candidates; and continue from a selected game into a cited rules guide.",
                "好，先停在这里。想继续时，我会从当前对话接上。");
        assertThat(catalog.calls).isZero();
        assertThat(model.naturalReplyCalls).hasValue(0);
    }

    @Test
    void reportsEveryRealActionPhaseWithDecisionAndToolCounters() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3}"),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        threeSelectionRecommendation("Glass Orchard 标注 30..45 分钟。"))));
        List<ProgressUpdate> progress = new ArrayList<>();

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我三个不同方向的候选"),
                "zh-CN",
                null,
                progress::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(progress).anySatisfy(update -> {
            assertThat(update.action()).isEqualTo(ProgressAction.BROWSE_BGG_CATALOG);
            assertThat(update.phase()).isEqualTo(ProgressPhase.STARTED);
            assertThat(update.decisionCycle()).isEqualTo(1);
            assertThat(update.actionCalls()).isEqualTo(1);
        });
        assertThat(progress).anySatisfy(update -> {
            assertThat(update.action()).isEqualTo(ProgressAction.BROWSE_BGG_CATALOG);
            assertThat(update.phase()).isEqualTo(ProgressPhase.COMPLETED);
            assertThat(update.catalogCalls()).isEqualTo(1);
            assertThat(update.verifiedCandidates()).isEqualTo(3);
        });
        assertThat(progress.stream()
                        .filter(update -> update.action() == ProgressAction.CHOOSE_NEXT_ACTION)
                        .filter(update -> update.phase() == ProgressPhase.STARTED))
                .hasSize(2)
                .extracting(ProgressUpdate::decisionCycle)
                .containsExactly(1, 2);
        assertThat(progress).anySatisfy(update -> {
            assertThat(update.action()).isEqualTo(ProgressAction.RECOMMEND_GAMES);
            assertThat(update.phase()).isEqualTo(ProgressPhase.COMPLETED);
            assertThat(update.decisionCycle()).isEqualTo(2);
            assertThat(update.modelCalls()).isEqualTo(2);
            assertThat(update.actionCalls()).isEqualTo(2);
        });
    }

    @Test
    void treatsAStandaloneOriginalTitleAsContinuationAndUsesObservedMechanics() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "continue corrections and references in context",
                                    "later corrections replace earlier values",
                                    "runMemory is authoritative");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("preferenceUpdates", "evidence");
                    assertThat(request.messages().get(1).content())
                            .contains("想找和《马赛克花园》机制接近的游戏", "Mosaic Field");
                    return action(
                            "resolve",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("Mosaic Field", "Pattern Building", "Tile Placement");
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.SEARCH_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.ASK_TOOL);
                    return action(
                            "search",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "Pattern Building",
                                    "Open Drafting",
                                    "\"comparisonReferenceBggIds\":[50]");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"明白，Mosaic Field 是你补充的原名，不是换了话题。按刚核对到的图案构筑与板块放置，我会先看这两款：一款更纯粹，一款互动更明显。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]} ");
                }));
        List<DialogueMessage> transcript = List.of(
                new DialogueMessage("user", "想找和《马赛克花园》机制接近的游戏"),
                new DialogueMessage("assistant", "这个中文名可能对应不止一款，你还记得原名吗？"),
                new DialogueMessage("user", "Mosaic Field"));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Mosaic Field",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.assistantMessage()).contains("指定的参照", "2 款候选");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.games().getFirst().matches()).anyMatch(value -> value.contains("Pattern Building"));
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
        assertThat(response.harness().catalogCalls()).isEqualTo(3);
        assertThat(model.calls).hasValue(3);
    }

    @Test
    void honorsAGroundedPreferenceCorrectionAfterReferenceAndCandidateFactsWereObserved() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "resolve-reference",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}"),
                ignored -> action(
                        "inspect-old-slate",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "late-correction",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我按你最新的时长要求重新筛选。\","
                                + "\"selections\":[{\"bggId\":61}],"
                                + "\"preferenceUpdates\":[{\"field\":\"maxMinutes\",\"value\":55,\"evidence\":\"U1\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "FINAL_ID_FAILS_HARD_GATES",
                                    "\"durationMinutes\":{\"minimum\":null,\"maximum\":55,\"strength\":\"HARD\"",
                                    "\"recommendableBggIds\":[60]",
                                    "RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[60]");
                    return action(
                            "recommend-reconsidered-slate",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"现在只保留符合你刚补充时长的候选。\","
                                    + "\"selections\":[{\"bggId\":60}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "想找和 Mosaic Field 类似的游戏，不过最多只能玩55分钟",
                        List.of(),
                        List.of(new DialogueMessage(
                                "user", "想找和 Mosaic Field 类似的游戏，不过最多只能玩55分钟")),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().maxMinutes()).isEqualTo(55);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60);
        assertThat(response.harness().actions()).containsSubsequence(
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "UPDATE_PREFERENCES",
                "RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE",
                "REJECTED_ACTION:FINAL_ID_FAILS_HARD_GATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void reconsidersPreviouslyShownCardsAfterAnExplicitHardPreferenceCorrection() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-after-duration-correction",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"maxMinutes\",\"value\":55,\"evidence\":\"U1\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("\"recommendableBggIds\":[60]");
                    return action(
                            "recommend-still-fitting-shown-card",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"时长改短后，刚才这款仍然满足新上限，我保留它并按新条件说明取舍。\","
                                    + "\"selections\":[{\"bggId\":60}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                null,
                                90,
                                null,
                                BggGameType.ALL,
                                InteractionPreference.ANY),
                        "时间改成最多 55 分钟，其他条件保留。",
                        List.of(),
                        List.of(new DialogueMessage("user", "时间改成最多 55 分钟，其他条件保留。")),
                        null,
                        List.of(),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().maxMinutes()).isEqualTo(55);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60);
    }

    @Test
    void rejectsATruncatedPlayerTitleBeforeBggAndLetsTheAgentRetryTheIntactSpan() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "truncated-reference",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"osaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}"),
                request -> {
                    assertThat(catalog.calls).isZero();
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "REFERENCE_TITLE_NOT_GROUNDED",
                                    "complete, intact title span",
                                    "\"referenceResolutionAttempts\":0");
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.RESOLVE_TOOL);
                    return action(
                            "intact-reference",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}");
                },
                ignored -> action(
                        "candidate-titles",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"已按你补充的完整原名核对，下面两款分别保留了不同的共同机制。\","
                                + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}")));
        List<DialogueMessage> transcript = List.of(
                new DialogueMessage("user", "想找和《马赛克花园》机制接近的游戏"),
                new DialogueMessage("assistant", "你还记得它的原名吗？"),
                new DialogueMessage("user", "Mosaic Field"));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Mosaic Field",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:REFERENCE_TITLE_NOT_GROUNDED",
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.games()).allSatisfy(game -> assertThat(game.matches())
                .anyMatch(match -> match.startsWith("与参考游戏共有的 BGG 机制/类型：")));
        assertThat(catalog.calls).isEqualTo(3);
    }

    @Test
    void keepsPlayerNamedReferencesOutOfTheAgentCandidateInspectionTool() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "mixed-reference-and-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Mosaic Field\",\"Glass Orchard\"]}"),
                request -> {
                    assertThat(catalog.calls).isZero();
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "PLAYER_NAMED_TITLE_REQUIRES_RESOLUTION",
                                    "only for your own new recommendation hypotheses");
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.RESOLVE_TOOL,
                                    BoardGameRecommendationAgent.SEARCH_TOOL);
                    return action(
                            "resolve-reference",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}");
                },
                ignored -> action(
                        "candidate-titles",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我先把参考对象和新候选分开核对，再按共同机制给你两种方向。\","
                                + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Mosaic Field",
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "想找和一款拼花游戏机制接近的桌游"),
                                new DialogueMessage("assistant", "原名是什么？"),
                                new DialogueMessage("user", "Mosaic Field")),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:PLAYER_NAMED_TITLE_REQUIRES_RESOLUTION",
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
        assertThat(response.games()).allSatisfy(game -> assertThat(game.matches())
                .anyMatch(match -> match.startsWith("与参考游戏共有的 BGG 机制/类型：")));
    }

    @Test
    void persistsIndependentHardPreferencesBeforeReferenceFactsBecomeVisible() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "resolve-with-player-count",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Mosaic Field\",\"purpose\":\"DISCUSSION_SUBJECT\",\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"U1\"}]}"),
                ignored -> action(
                        "reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"Mosaic Field 已核对，双人条件也记下了。你想继续看相近游戏，还是先聊它本身？\",\"referencedBggIds\":[50]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "Mosaic Field，2 人"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES", "RESOLVE_BGG_REFERENCE", "REPLY_TO_USER");
    }

    @Test
    void canDiscoverSourceBackedTitlesThenResolveAndVerifyThemThroughBgg() {
        TrackingCatalog catalog = catalog();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                assertThat(request.query()).contains("shared spatial pattern", "low conflict");
                return Optional.of(new CandidateDiscovery(
                        List.of(
                                new CandidateLead("Glass Orchard", "Uses a shared spatial pattern", List.of(1)),
                                new CandidateLead("Loom City", "Low-conflict drafting", List.of(2))),
                        List.of(
                                new Source(1, "Independent guide", "https://example.test/guide", "example.test"),
                                new Source(2, "Closed social post", "https://www.facebook.com/example", "www.facebook.com"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.DISCOVER_TOOL);
                    assertThat(request.messages().get(1).content())
                            .contains(
                                    "\"semanticPublicDiscovery\":true",
                                    "\"subjectiveFitResearch\":false");
                    return action(
                            "inspect-empty",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Unknown Spatial Design\"]}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.DISCOVER_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.RESEARCH_TOOL);
                    return action(
                        "discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"games with shared spatial pattern building and low conflict\",\"types\":[\"ABSTRACT\"]}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .containsExactly(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.RESEARCH_TOOL,
                                    BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "Independent guide",
                                    "Glass Orchard",
                                    "Loom City",
                                    "Uses a shared spatial pattern",
                                    "already resolved and hydrated",
                                    "\"recommendableBggIds\":[60,61]",
                                    "\"semanticPublicDiscovery\":false",
                                    "\"subjectiveFitResearch\":true")
                            .doesNotContain("\"candidateBggIds\"");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[60, 61]");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我先按你描述的空间拼搭感找了公开候选，再逐一核对了 BGG；这两款方向不同，你可以从互动强弱来选。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]} ");
                }));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想要有空间拼搭感、冲突别太强的桌游"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.researchSources()).singleElement().satisfies(source ->
                assertThat(source.title()).isEqualTo("Independent guide"));
        assertThat(response.games().getFirst().reasons())
                .anySatisfy(reason -> assertThat(reason.kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.WEB_RESEARCH));
        assertThat(response.games().get(1).reasons())
                .noneSatisfy(reason -> assertThat(reason.kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.WEB_RESEARCH));
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void canDiscoverAnExternallyGroundedRelationshipBeforeGuessingCandidateTitles() {
        TrackingCatalog catalog = catalog();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                throw new AssertionError("relationship discovery must use the single candidate-discovery call");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                assertThat(request.query()).contains("2025", "North Star jury prize", "winner");
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Glass Orchard",
                                "The official jury archive identifies this as the unique 2025 winner.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Official jury archive",
                                "https://jury.example.test/awards/2025",
                                "jury.example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.DISCOVER_TOOL)
                            .contains(BoardGameRecommendationAgent.SEARCH_TOOL);
                    assertThat(request.messages().get(1).content())
                            .contains("\"semanticPublicDiscovery\":true");
                    return action(
                        "discover-relation",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"2025 North Star jury prize board game winner\"}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .containsExactly(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.RESEARCH_TOOL,
                                    BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "Official jury archive",
                                    "unique 2025 winner",
                                    "\"recommendableBggIds\":[60]",
                                    "\"semanticPublicDiscovery\":false");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我先核对了外部关系，再用 BGG 确认游戏身份；这张卡片就是可继续选择的结果。\","
                                    + "\"referenceBggIds\":[],\"selections\":[{\"bggId\":60}]}");
                }));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "I want to play the 2025 North Star jury prize winner"),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60);
        assertThat(response.researchSources()).singleElement().satisfies(source ->
                assertThat(source.domain()).isEqualTo("jury.example.test"));
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void carriesAttributedDiscoveryEvidenceDirectlyIntoTheCardWithoutModelWrittenNarrative() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "这不是只按年份猜经典：我先核对了公开档案里的获奖记录，再确认了游戏身份。";
        String rawObservation = "The independent archive records Glass Orchard as an award recipient. "
                + "The complete attributed note remains available to the recommendation model. ".repeat(10);
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                throw new AssertionError("the discovery result already contains the requested external evidence");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Glass Orchard",
                                rawObservation,
                                List.of(1, 2, 3, 4, 5, 6, 7))),
                        List.of(
                                new Source(1, "Award archive 1", "https://one.example.test/archive", "one.example.test"),
                                new Source(2, "Award archive 2", "https://two.example.test/archive", "two.example.test"),
                                new Source(3, "Award archive 3", "https://three.example.test/archive", "three.example.test"),
                                new Source(4, "Award archive 4", "https://four.example.test/archive", "four.example.test"),
                                new Source(5, "Award archive 5", "https://five.example.test/archive", "five.example.test"),
                                new Source(6, "Award archive 6", "https://six.example.test/archive", "six.example.test"),
                                new Source(7, "Award archive 7", "https://seven.example.test/archive", "seven.example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "discover-awarded-classic",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"award-winning older board games that still hold up\"}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"id\":\"R60:1\"",
                                    "The independent archive records Glass Orchard as an award recipient",
                                    rawObservation.substring(rawObservation.length() - 80),
                                    "\"sourceIndexes\":[1,2,3,4,5,6,7]");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .doesNotContain("R60:1", "why", "tradeoff", "internalEvidenceIds");
                    return action(
                            "recommend-with-attributed-evidence",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"" + rawMessage + "\",\"selections\":[{\"bggId\":60}]}" );
                }));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "你好，我想玩获过奖的经典老游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已用公开来源确认关系，并通过 BGG 核对出 1 款候选。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons())
                    .filteredOn(reason -> reason.kind() == BoardGameRecommendationAgent.ReasonKind.WEB_RESEARCH)
                    .singleElement()
                    .satisfies(reason -> {
                assertThat(reason.text()).isEqualTo(rawObservation);
                assertThat(reason.sourceIndexes()).containsExactly(1, 2, 3, 4, 5, 6, 7);
            });
            assertThat(game.tradeoffs()).isEmpty();
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void coalescesProviderParallelAlternativesForTheSameSideEffectFreeRead() {
        TrackingCatalog catalog = catalog();
        AtomicInteger discoveryCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                throw new AssertionError("candidate discovery is sufficient");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                assertThat(request.query()).isEqualTo("older award winners with lasting play value");
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Glass Orchard",
                                "An archive identifies it as an older award recipient.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Archive",
                                "https://archive.example.test/winners",
                                "archive.example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> new Turn(
                        "",
                        List.of(
                                new ToolCall(
                                        "first-discovery",
                                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                                        "{\"query\":\"older award winners with lasting play value\",\"contextualGroup\":null,\"preferenceUpdates\":[]}"),
                                new ToolCall(
                                        "alternative-discovery",
                                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                                        "{\"query\":\"classic prize-winning tabletop games\",\"contextualGroup\":null,\"preferenceUpdates\":[]}"))),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我先核对公开获奖记录，再确认了游戏身份；这款可以直接开桌。\","
                                + "\"selections\":[{\"bggId\":60}]}")));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找有旧奖杯背书、今天仍值得开的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(discoveryCalls).hasValue(1);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "COALESCED_PARALLEL_READ_ACTIONS:2",
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void canRecoverFromMistakingACreatorAliasForAGameTitleByChangingTools() {
        TrackingCatalog catalog = catalog();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                assertThat(request.query()).contains("Clockmaker", "designer");
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Glass Orchard",
                                "The creator's catalog lists this title.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Creator catalog",
                                "https://creator.example.test/catalog",
                                "creator.example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "mistaken-title",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Clockmaker\",\"purpose\":\"TARGET_GAME\"}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "did not resolve as a game title",
                                    "creator/person alias",
                                    "use public discovery");
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.DISCOVER_TOOL);
                    return action(
                            "recover-with-relation-discovery",
                            BoardGameRecommendationAgent.DISCOVER_TOOL,
                            "{\"query\":\"board games by the designer nicknamed Clockmaker\"}");
                },
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"刚才把人物别名误当成了游戏名；现在已按创作者关系查证并核对 BGG 身份。\","
                                + "\"selections\":[{\"bggId\":60}]}")));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "I want a game by the designer called Clockmaker"),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE",
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void exposesOnlyHardGateEligibleSelectionsAfterSemanticDiscovery() {
        TrackingCatalog catalog = catalog();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                throw new AssertionError("ordinary recommendation must not trigger a second web call");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(
                                new CandidateLead("Glass Orchard", "Fits the requested pattern play", List.of(1)),
                                new CandidateLead("Loom City", "Another pattern candidate", List.of(1))),
                        List.of(new Source(1, "Independent guide", "https://example.test/guide", "example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-empty",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Unknown Pattern Design\"]}"),
                ignored -> action(
                        "discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"pattern games for four players within 55 minutes\"}"),
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .containsExactly(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.RESEARCH_TOOL,
                                    BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[60]")
                            .doesNotContain("\"enum\":[60, 61]");
                    assertThat(request.messages().getLast().content())
                            .contains("\"recommendableBggIds\":[60]");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我按四人和 55 分钟上限筛过了，先保留真正满足硬条件的这一款。\","
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));
        RecommendationProfile profile = new RecommendationProfile(
                4, 55, null, BggGameType.ALL, InteractionPreference.ANY);

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(profile, "四个人，55 分钟内，想玩图案构筑"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60);
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).doesNotContain("RESEARCH_GAME_FIT", "REJECTED_ACTION:FINAL_ID_FAILS_HARD_GATES");
    }

    @Test
    void removesWebResearchActionsAfterAProviderFailureAndLetsTheAgentRecoverWithBgg() {
        TrackingCatalog catalog = catalog();
        AtomicInteger discoveryCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                throw new AssertionError("fit research must disappear with the failed provider capability");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                throw new WebResearchUnavailableException("PROVIDER_IO_ERROR");
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.DISCOVER_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.RESEARCH_TOOL);
                    return action(
                            "inspect-empty",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Unknown Spatial Design\"]}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.DISCOVER_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.RESEARCH_TOOL);
                    return action(
                            "discover",
                            BoardGameRecommendationAgent.DISCOVER_TOOL,
                            "{\"query\":\"low-conflict spatial pattern games\",\"types\":[\"ABSTRACT\"]}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .doesNotContain(
                                    BoardGameRecommendationAgent.DISCOVER_TOOL,
                                    BoardGameRecommendationAgent.RESEARCH_TOOL);
                    assertThat(request.messages()).hasSize(4);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "PROVIDER_IO_ERROR",
                                    "\"semanticPublicDiscovery\":false",
                                    "do not retry web research");
                    return action(
                            "browse",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            "{\"limit\":8}");
                },
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"公开语义搜索这轮没有响应，我改用仍可用的 BGG 身份和机制资料核对了候选；两张卡片分别偏向图案构筑与板块选择。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}")));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找空间拼搭、冲突不强的桌游"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(discoveryCalls).hasValue(1);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains("WEB_RESEARCH_DEGRADED:PROVIDER_IO_ERROR", "RECOMMEND_GAMES")
                .doesNotContain("REJECTED_UNAVAILABLE_ACTION");
    }

    @Test
    void carriesAtomicPreferencesAndVerifiedFactsAcrossACompositeRead() {
        TrackingCatalog catalog = catalog();
        List<Integer> requestCharacters = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    requestCharacters.add(requestCharacters(request));
                    assertThat(request.messages()).hasSize(2);
                    return action(
                            "search",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"U1\"}]}");
                },
                request -> {
                    requestCharacters.add(requestCharacters(request));
                    assertThat(request.messages()).hasSize(4);
                    assertThat(request.tools())
                            .as("the model must not be invited to resubmit preferences already captured in this run")
                            .allSatisfy(tool -> assertThat(tool.inputSchema())
                                    .doesNotContain("preferenceUpdates"));
                    assertThat(request.messages().stream()
                                    .filter(message -> message.role() == BoardGameRecommendationModel.Role.TOOL))
                            .hasSize(1);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"playerCount\":{\"minimum\":2,\"maximum\":2,\"strength\":\"HARD\"",
                                    "Glass Orchard",
                                    "Pattern Building");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你补充的双人条件，我保留两种不同侧重的图案构筑方向，具体差异见卡片。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "2 人，想找图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES", "SEARCH_BGG_BY_NAME", "LOOKUP_BGG_CANDIDATES", "RECOMMEND_GAMES");
        assertThat(requestCharacters)
                .as("the role-correction contract remains inside the bounded recommendation context")
                .allSatisfy(size -> assertThat(size).isLessThan(20_000));
    }

    @Test
    void latestExplicitTurnReplacesPreviouslyPersistedPlayerCountAndGameType() {
        TrackingCatalog catalog = catalog(BggGameType.PARTY);
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains(
                                    "\"playerCount\":{\"minimum\":4,\"maximum\":4,\"strength\":\"HARD\"",
                                    "\"type\":\"STRATEGY\"",
                                    "\"evidenceId\":\"U1\"",
                                    "\"evidenceId\":\"U2\"",
                                    "现在只有两个人",
                                    "改成派对游戏");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[\"U1\",\"U2\"]");
                    assertThat(request.messages().getFirst().content())
                            .contains("later corrections replace earlier values");
                    return action(
                            "inspect-after-correction",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                    + "\"preferenceUpdates\":["
                                    + "{\"field\":\"players\",\"value\":2,\"evidence\":\"U2\"},"
                                    + "{\"field\":\"type\",\"value\":\"PARTY\",\"evidence\":\"U2\"}]} ");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"playerCount\":{\"minimum\":2,\"maximum\":2,\"strength\":\"HARD\"",
                                    "\"type\":\"PARTY\"")
                            .doesNotContain("GAME_TYPE_EVIDENCE_MISMATCH");
                    return action(
                            "recommend-after-correction",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"收到，你刚改成两个人和派对类型了；我按最新条件重新核对了这两张卡。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}");
                }));
        List<DialogueMessage> transcript = List.of(
                new DialogueMessage("user", "我们四个人，想玩策略游戏。"),
                new DialogueMessage("assistant", "好，我记下四个人和策略游戏。"),
                new DialogueMessage("user", "等等我改口了，现在只有两个人，而且不想玩策略了，改成派对游戏。"));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                4, null, null, BggGameType.STRATEGY, InteractionPreference.ANY),
                        "等等我改口了，现在只有两个人，而且不想玩策略了，改成派对游戏。",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.profile().type()).isEqualTo(BggGameType.PARTY);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES", "SEARCH_BGG_BY_NAME", "LOOKUP_BGG_CANDIDATES", "RECOMMEND_GAMES");
    }

    @Test
    void treatsAnUnchangedPreferenceUpdateAsAnIdempotentNoOpWithoutRequiringFreshEvidence() {
        TrackingCatalog catalog = catalog(BggGameType.PARTY);
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-with-redundant-profile",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"],\"preferenceUpdates\":["
                                + "{\"field\":\"players\",\"value\":2,\"evidence\":\"U1\"},"
                                + "{\"field\":\"type\",\"value\":\"PARTY\",\"evidence\":\"U1\"}]}"),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我沿用刚才已经确认的条件，给你两款经过核对的候选。\","
                                + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                2, null, null, BggGameType.PARTY, InteractionPreference.ANY),
                        "好，现在按刚改的新条件给我两款。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.profile().type()).isEqualTo(BggGameType.PARTY);
        assertThat(response.harness().actions()).containsExactly(
                "IGNORED_REDUNDANT_PREFERENCE_UPDATE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsAUsefulCompositeReadWhenItsAttachedPreferenceEvidenceIdNeedsCorrection() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search-with-paraphrased-evidence",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":4,\"evidence\":\"U99\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_EVIDENCE_NOT_GROUNDED", "Glass Orchard", "Pattern Building");
                    return action(
                            "recommend-with-grounded-evidence",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按四人条件，我先给你一个经过核对的方向，详情见卡片。\","
                                    + "\"selections\":[{\"bggId\":60}],"
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\"}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们四个人想玩图案构筑"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_NOT_GROUNDED",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "UPDATE_PREFERENCES",
                "RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE",
                "RECOMMEND_GAMES");
    }

    @Test
    void citesStableUserMessageIdsForHardPreferencesWithoutAFormattingRetry() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools())
                            .extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.DISCOVER_TOOL);
                    assertThat(request.messages().get(1).content())
                            .contains(
                                    "\"evidenceId\":\"U1\"",
                                    "\"evidenceId\":\"U2\"",
                                    "我们四个人聚会",
                                    "90 分钟内");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains("\"enum\":[\"U1\",\"U2\"]")
                            .doesNotContain("Copy one exact contiguous substring");
                    return action(
                            "inspect-with-message-citations",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\"],"
                                    + "\"preferenceUpdates\":["
                                    + "{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\"},"
                                    + "{\"field\":\"maxMinutes\",\"value\":90,\"evidence\":\"U2\"}]} ");
                },
                request -> {
                    assertThat(request.tools())
                            .extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.RECOMMEND_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.ASK_TOOL,
                                    BoardGameRecommendationAgent.RESOLVE_TOOL);
                    assertThat(requestCharacters(request)).isLessThan(16_000);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"playerCount\":{\"minimum\":4,\"maximum\":4,\"strength\":\"HARD\"",
                                    "\"durationMinutes\":{\"minimum\":null,\"maximum\":90,\"strength\":\"HARD\"",
                                    "\"interaction\":\"ANY\"");
                    return action(
                            "recommend-without-repeating-preferences",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"人数和时间都记下了，我先给你三款经过核对的方向。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61},{\"bggId\":63}]} ");
                }));
        List<DialogueMessage> transcript = List.of(
                new DialogueMessage("user", "嗨，我们四个人聚会，但还没想清楚玩什么。"),
                new DialogueMessage("assistant", "你更想要轻松热闹，还是有一点对抗？"),
                new DialogueMessage("user", "想要图案构筑，有两个新手，90 分钟内。"));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "想要图案构筑，有两个新手，90 分钟内。",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().maxMinutes()).isEqualTo(90);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void treatsZeroAsAnOpenDurationLowerBoundOnlyWhenTheCitedUserTextStatesThePositiveCeiling() {
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "reply-with-provider-open-bound-sentinel",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"已记下 90 分钟上限。\",\"preferenceUpdates\":[{"
                                + "\"field\":\"durationMinutes\",\"value\":{\"minimum\":0,\"maximum\":90},"
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                ignored -> action(
                        "fallback-after-rejected-sentinel",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"我先不记录时长边界。\"}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "有两个新手，90 分钟内。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNotNull();
        assertThat(response.profile().durationMinutes().minimum()).isNull();
        assertThat(response.profile().durationMinutes().maximum()).isEqualTo(90);
        assertThat(response.assistantMessage()).isEqualTo("已记下 90 分钟上限。");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "REPLY_TO_USER");
    }

    @Test
    void rejectsAZeroDurationLowerBoundWhenThePlayerActuallyStatesZero() {
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "reply-with-player-authored-zero",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"已记录 0 到 90 分钟。\",\"preferenceUpdates\":[{"
                                + "\"field\":\"durationMinutes\",\"value\":{\"minimum\":0,\"maximum\":90},"
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                ignored -> action(
                        "reply-without-invalid-duration",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"0 分钟不在可记录的游玩时长范围内，请换一个正数下限。\"}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "明确是 0 到 90 分钟。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:DURATION_OUT_OF_RANGE",
                "REPLY_TO_USER");
    }

    @Test
    void preservesPlayerAndDurationRangesAsAtomicGroundedPreferenceUpdates() {
        assertThat(BoardGameRecommendationAgent.PROMPT_VERSION)
                .isEqualTo("recommendation-agent-v17-id-only-selection");
        assertThat(BoardGameRecommendationAgent.class.getResource(
                        "/prompts/recommendation-agent-v1-system.txt"))
                .as("the production-replayed v1 prompt remains reproducible after activating v2")
                .isNotNull();
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    String schema = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.SEARCH_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema();
                    assertThat(schema)
                            .contains(
                                    "playerCount",
                                    "durationMinutes",
                                    "minimum",
                                    "maximum",
                                    "\"enum\":[\"players\",\"playerCount\"");
                    return action(
                            "keep-both-range-bounds",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],\"preferenceUpdates\":["
                                    + "{\"field\":\"playerCount\",\"value\":{\"minimum\":3,\"maximum\":4},\"evidence\":\"U1\"},"
                                    + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":120,\"maximum\":180},\"evidence\":\"U1\"}]} ");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"runMemory\":{\"profile\":{\"playerCount\":{\"minimum\":3,\"maximum\":4,\"strength\":\"HARD\"",
                                    "\"durationMinutes\":{\"minimum\":120,\"maximum\":180,\"strength\":\"HARD\"",
                                    "\"sourceText\":\"3–4 人，120–180 分钟都是硬条件。\"",
                                    "\"confirmedTurn\":1",
                                    "\"recommendableBggIds\":[]");
                    assertThat(request.messages().getLast().content())
                            .doesNotContain(
                                    "\"profile\":{\"players\"",
                                    "\"minMinutes\":120",
                                    "\"maxMinutes\":180");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.NO_MATCH_TOOL);
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.NO_MATCH_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains(
                                    "\"enum\":[\"durationMinutes\"]",
                                    "\"required\":[\"relaxSubject\",\"message\"]");
                    return action(
                            "honest-zero-match",
                            BoardGameRecommendationAgent.NO_MATCH_TOOL,
                            "{\"relaxSubject\":\"durationMinutes\","
                                    + "\"message\":\"这批候选里，3–4 人和 120–180 分钟这两条硬条件没有同时成立。若只动一处，我建议先确认是否愿意放宽时长；在你点头前我不会改掉这条条件。\"}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "3–4 人，120–180 分钟都是硬条件。",
                        List.of(),
                        List.of(new DialogueMessage("user", "3–4 人，120–180 分钟都是硬条件。")),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(response.games()).isEmpty();
        assertThat(response.assistantMessage()).contains("没有同时成立", "建议先确认", "不会改掉这条条件");
        assertThat(response.clarification().options()).singleElement().satisfies(option -> {
            assertThat(option.label()).contains("取消", "120–180 分钟", "其他条件保持不变");
            assertThat(option.value()).isEqualTo(option.label());
        });
        assertThat(response.profile().minPlayers()).isEqualTo(3);
        assertThat(response.profile().maxPlayers()).isEqualTo(4);
        assertThat(response.profile().minMinutes()).isEqualTo(120);
        assertThat(response.profile().maxMinutes()).isEqualTo(180);
        assertThat(response.userModel().summary()).contains("3–4 人", "120–180 分钟");
        assertThat(response.harness().actions()).contains("REPORT_NO_MATCH");
    }

    @Test
    void preservesLongPlayerAuthoredUnicodeAndWhitespaceWithoutAnArbitraryPerTurnLimit() {
        String accepted = "😀".repeat(1_495) + "  A\n中";
        assertThat(accepted.codePointCount(0, accepted.length())).isEqualTo(1_500);
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().get(1).content())
                    .contains("😀😀😀", "  A\\n中")
                    .doesNotContain(" 😀");
            return action(
                    "acknowledge-boundary-message",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我完整收到了这段输入。\"}");
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), accepted),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
    }

    @Test
    void keepsACompleteAssistantTurnBeyondThePlayerInputBoundary() {
        String assistantTurn = "  " + "答".repeat(2_400) + "  ";
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().get(1).content()).contains(assistantTurn);
            return action(
                    "continue-after-complete-assistant-turn",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我会接着完整回复继续。\"}");
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "继续",
                        List.of(),
                        List.of(new DialogueMessage("assistant", assistantTurn)),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo("我会接着完整回复继续。");
    }

    @Test
    void keepsTheFinalRecommendationPayloadSmallAndLeavesCardFactsToTheApplication() {
        String rawMessage = "先给你一个经过核对的方向，事实与边界都在卡片里。";
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                request -> {
                    ToolSpec recommendation = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    JsonNode properties = schema(recommendation).path("properties");
                    JsonNode selection = properties.path("selections").path("items").path("properties");
                    assertThat(properties.has("message")).isFalse();
                    assertThat(selection.size()).isEqualTo(1);
                    assertThat(selection.has("bggId")).isTrue();
                    assertThat(selection.has("why")).isFalse();
                    assertThat(selection.has("tradeoff")).isFalse();
                    assertThat(selection.has("internalEvidenceIds")).isFalse();
                    return action(
                            "recommend-small-decision",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"" + rawMessage + "\",\"selections\":[{\"bggId\":60}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "请完整讲清楚一款候选。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).extracting(reason -> reason.kind())
                    .containsOnly(BoardGameRecommendationAgent.ReasonKind.BGG_FACT);
            assertThat(game.tradeoffs()).singleElement().asString()
                    .contains("BGG 标签", "不能证明");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void explicitlyClearsNumericConstraintsWithoutErasingUnmentionedPreferences() {
        RecommendationProfile current = new RecommendationProfile(
                4, 90, new BigDecimal("3.2"), BggGameType.STRATEGY, InteractionPreference.COMPETITIVE);
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            ToolSpec reply = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.REPLY_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
            JsonNode valueVariants = schema(reply)
                    .at("/properties/preferenceUpdates/items/properties/value/anyOf");
            assertThat(valueVariants.findValuesAsText("type")).contains("null");
            return action(
                    "clear-two-numeric-limits",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"好，时长和复杂度都不再设限；四人和策略对抗偏好继续保留。\","
                            + "\"preferenceUpdates\":["
                            + "{\"field\":\"durationMinutes\",\"value\":null,\"evidence\":\"U1\"},"
                            + "{\"field\":\"complexity\",\"value\":null,\"evidence\":\"U1\"}]}" );
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(current, "时长和复杂度都不限，其他条件不变。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(response.profile().complexity()).isNull();
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().type()).isEqualTo(BggGameType.STRATEGY);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.COMPETITIVE);
        assertThat(response.harness().actions()).containsExactly("UPDATE_PREFERENCES", "REPLY_TO_USER");
    }

    @Test
    void acceptsANumericComplexityCeilingWithCitedUserEvidence() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search-with-explicit-ceiling",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"maxWeight\",\"value\":3.5,\"evidence\":\"U1\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"complexity\":{\"minimum\":null,\"maximum\":3.5,\"strength\":\"HARD\"",
                                    "Glass Orchard");
                    return action(
                            "recommend-with-explicit-ceiling",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我按你明确给出的复杂度上限挑了一个经过核对的候选，详情见卡片。\","
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "BGG 难度最多 3.5，想玩策略游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().maxWeight()).isEqualByComparingTo("3.5");
        assertThat(response.harness().actions()).contains("UPDATE_PREFERENCES", "RECOMMEND_GAMES");
    }

    @Test
    void rejectsAnInventedComplexityRangeForAQualitativeTasteWithoutRetryingSafeText() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-with-invented-complexity-number",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"你说的是定性的重策偏好，我不会替你换算成 BGG 数字。\","
                        + "\"preferenceUpdates\":[{\"field\":\"complexity\","
                        + "\"value\":{\"minimum\":3.5,\"maximum\":5},\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"DIRECT\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我想玩重策，但没有给 BGG 复杂度数字。先别给卡片。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().complexity()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT",
                "REPLY_TO_USER");
    }

    @Test
    void doesNotUseAnExplicitPlayerCountAsComplexityEvidence() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-with-cross-field-number",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"我只记录明确的 5 人，不把人数当复杂度。\","
                        + "\"preferenceUpdates\":["
                        + "{\"field\":\"playerCount\",\"value\":5,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                        + "{\"field\":\"maxWeight\",\"value\":5,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们 5 人，想玩重策，但没给复杂度数字。"),
                "zh-CN");

        assertThat(response.profile().players()).isEqualTo(5);
        assertThat(response.profile().complexity()).isNull();
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT",
                "UPDATE_PREFERENCES",
                "REPLY_TO_USER");
    }

    @Test
    void keepsDirectHardPreferencesWhileIgnoringAnUnconfirmedGuessFromAClarificationAction() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "ask-with-unconfirmed-mode",
                BoardGameRecommendationAgent.ASK_TOOL,
                "{\"question\":\"这两种方向会改变候选集合。你更偏短局还是长局？\","
                        + "\"options\":[\"偏短局\",\"偏长局\"],"
                        + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":5,\"evidence\":\"U1\"},{"
                        + "\"field\":\"interaction\",\"value\":\"COOPERATIVE\","
                        + "\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们有 5 个人，想找一种围着壁炉讲秘密的感觉"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.profile().players()).isEqualTo(5);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.clarification().options())
                .extracting(BoardGameRecommendationAgent.ClarificationOption::value)
                .containsExactly("偏短局", "偏长局");
        assertThat(response.harness().actions())
                .containsExactly(
                        "REJECTED_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID",
                        "UPDATE_PREFERENCES",
                        "ASK_USER");
    }

    @Test
    void rejectsAnUnknownEvidenceIdWithoutDiscardingTheUsefulCatalogRead() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-with-unknown-evidence-id",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COOPERATIVE\",\"evidence\":\"U99\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_EVIDENCE_NOT_GROUNDED", "Glass Orchard");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"无效的证据引用没有改变偏好；我先给你一个经过核对的方向。\","
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "合作游戏已经玩腻了，想换换口味"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_NOT_GROUNDED",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void persistsLateExplicitHardPreferencesInsideTheFinalRecommendationAction() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                request -> {
                    var finalAction = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    JsonNode preference = schema(finalAction).at("/properties/preferenceUpdates/items");
                    assertThat(schema(finalAction).at("/properties").has("message")).isFalse();
                    assertThat(textValues(preference.path("required")))
                            .containsExactly("field", "value", "evidence", "evidenceClassification");
                    assertThat(textValues(preference.at("/properties/field/enum")))
                            .contains("players", "interaction");
                    assertThat(preference.at("/properties/value/anyOf").toString())
                            .contains("COMPETITIVE");
                    assertThat(textValues(preference.at("/properties/evidenceClassification/enum")))
                            .containsExactly("DIRECT", "CONTEXTUAL_COMPLETE_GROUP");
                    assertThat(preference.at("/properties/evidence/description").asText())
                            .contains("current user-message", "participant group", "never cite game facts");
                    assertThat(preference.at("/properties/evidenceClassification/description").asText())
                            .contains("speaker plus every companion", "reversible working assumption");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你补充的双人条件，我保留两种图案构筑方向，具体差异见卡片。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}],"
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"U2\"}]}");
                }));
        List<DialogueMessage> transcript = List.of(
                new DialogueMessage("user", "想找和花砖物语接近的游戏"),
                new DialogueMessage("assistant", "你更看重哪一部分？"),
                new DialogueMessage("user", "我喜欢开放轮抽和图案构建，而且想要两个人也好玩"));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我喜欢开放轮抽和图案构建，而且想要两个人也好玩",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.harness().actions()).containsSubsequence("UPDATE_PREFERENCES", "RECOMMEND_GAMES");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60, 61);
    }

    @Test
    void returnsAnInvalidFinalIdToTheAgentAndLetsItRecover() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "bad-final",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先试这款。\",\"referenceBggIds\":[],\"selections\":[{\"bggId\":999}]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("FINAL_ID_NOT_VERIFIED");
                    return action(
                            "valid-final",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"刚才的候选没有经过核对；这次我只保留已经确认过资料的这一款。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "随便推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60);
        assertThat(response.harness().actions()).contains("REJECTED_ACTION:FINAL_ID_NOT_VERIFIED");
        assertThat(response.harness().fallbackUsed()).isFalse();
    }

    @Test
    void groundsPreferenceStateAndRejectsASelectedGameThatFailsItsHardGates() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Long Mosaic\",\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":["
                                + "{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\"},"
                                + "{\"field\":\"maxMinutes\",\"value\":60,\"evidence\":\"U1\"}]}"),
                ignored -> action(
                        "bad-gate",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看长局。\",\"referenceBggIds\":[],\"selections\":[{\"bggId\":62}]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("FINAL_ID_FAILS_HARD_GATES");
                    return action(
                            "good-gate",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你说的 4 人和一小时上限，长局不合适；这款才在范围内。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "4 个人，最多 60 分钟"),
                "zh-CN");

        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().maxMinutes()).isEqualTo(60);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60);
        assertThat(response.games().getFirst().matches())
                .anyMatch(value -> value.contains("4 人"))
                .anyMatch(value -> value.contains("上限内"));
        assertThat(response.harness().actions()).contains("REJECTED_ACTION:FINAL_ID_FAILS_HARD_GATES");
    }

    @Test
    void doesNotRepublishLegacyModelWrittenCardNarrativeFields() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "如果你想先从更短的一局开始，Glass Orchard 是这组里更直接的选择；\n它的取舍是人数支持较宽，但这不等于每个人数下体验都相同。";
        String rawWhy = "它标注 40..55 分钟、支持 2..4 人；\n这些原值让本次试选的人数与时间边界清楚可核对。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "grounded-final",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"如果你想先从更短的一局开始，Glass Orchard 是这组里更直接的选择；\\n它的取舍是人数支持较宽，但这不等于每个人数下体验都相同。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60,"
                                + ""
                                + "\"why\":\"它标注 40..55 分钟、支持 2..4 人；\\n这些原值让本次试选的人数与时间边界清楚可核对。\","
                                + "\"tradeoff\":\"资料只证明支持 2–4 人，不能据此断言所有人数下的互动强度一致。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B60:playerCount\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).allSatisfy(reason -> assertThat(reason.text()).isNotEqualTo(rawWhy));
            assertThat(game.reasons()).extracting(reason -> reason.text())
                    .contains("BGG 机制/类型标签：Pattern Building、Abstract Strategy");
            assertThat(game.tradeoffs()).singleElement().asString()
                    .contains("BGG 标签", "不能证明", "互动感", "等待时间");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsExactly(
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "RECOMMEND_GAMES");
    }

    @Test
    void ignoresAnUnsupportedContextualTypedPreferenceWithoutDiscardingVerifiedCards() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "你说的旧书柜和老奖杯更像选品方向，不是一个需要偷偷写进档案的硬类型；我先保留已核对的候选。";
        String rawWhy = "Glass Orchard 的身份与目录资料已经核对，适合作为这个开放方向的第一种选择。";
        String rawTradeoff = "现有资料没有把你的比喻收窄成唯一机制，下一轮仍可以按互动偏好继续筛。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "recommend-with-contextual-type",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\","
                                + "\"preferenceUpdates\":[{\"field\":\"type\",\"value\":\"STRATEGY\","
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}],"
                                + "\"selections\":[{\"bggId\":60,"
                                + "\"why\":\"" + rawWhy + "\","
                                + "\"tradeoff\":\"" + rawTradeoff + "\","
                                + "\"internalEvidenceIds\":[\"B60:mechanics\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我想从旧书柜里抽出一盒经得起时间的游戏：有老奖杯背书，但今天开桌也不会只剩情怀。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).allSatisfy(reason -> assertThat(reason.text()).isNotEqualTo(rawWhy));
            assertThat(game.tradeoffs()).allSatisfy(tradeoff -> assertThat(tradeoff).isNotEqualTo(rawTradeoff));
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "IGNORED_UNSUPPORTED_CONTEXTUAL_PREFERENCE",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsALifestyleInferenceAsAPersistentGameTypeWithoutDiscardingTheCatalogRead() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    ToolSpec browse = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(browse.description())
                            .contains(
                                    "complete group requires reversible exact playerCount",
                                    "companions never prove type");
                    JsonNode browseSchema = schema(browse);
                    assertThat(textValues(browseSchema.path("required")))
                            .contains("contextualGroup", "preferenceUpdates");
                    assertThat(browseSchema.at("/properties/contextualGroup/description").asText())
                            .contains("speaker plus every companion", "Visible assumption");
                    return action(
                            "browse-with-inferred-audience",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            "{\"limit\":3,\"contextualGroup\":{\"playerCount\":3,\"evidence\":\"U1\"},"
                                    + "\"preferenceUpdates\":[{\"field\":\"type\",\"value\":\"FAMILY\","
                                    + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}" );
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT")
                            .contains("\"contextualAssumptions\"")
                            .contains("Glass Orchard", "Loom City", "Signal Bazaar");
                    assertThat(request.messages().getLast().content()).contains("\"type\":\"ALL\"");
                    return action(
                            "recommend-without-inferred-type",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我没有把同行者身份偷换成游戏分类，先给你三个不同方向。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61},{\"bggId\":63}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚带导师和同事开桌，给我三款选择。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.games()).hasSize(3);
        assertThat(response.userModel().hypotheses()).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.field()).isEqualTo("playerCount");
            assertThat(hypothesis.value()).isEqualTo("3");
        });
        assertThat(response.harness().actions()).containsExactly(
                "RECORD_CONTEXTUAL_PREFERENCE",
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT",
                "SEARCH_BGG_CATALOG",
                "RECOMMEND_GAMES");
    }

    @Test
    void completesExplicitNumericSiblingsInsideTheSameCatalogReadWithoutAnotherModelRepair() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-with-partial-numeric-decision",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3,\"contextualGroup\":{\"playerCount\":4,\"evidence\":\"U1\"},"
                                + "\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,"
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("\"playerCount\":{\"minimum\":4,\"maximum\":4")
                            .contains("\"durationMinutes\":{\"minimum\":null,\"maximum\":60");
                    return action(
                            "recommend-under-complete-hard-gates",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你明确说的 4 人和最多 60 分钟，我只保留目录条件都能核对的两款。\","
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "就按 4 人，最多 60 分钟继续推荐。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().maxMinutes()).isEqualTo(60);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "SEARCH_BGG_CATALOG",
                "RECOMMEND_GAMES");
        assertThat(response.userModel().hypotheses()).isEmpty();
    }

    @Test
    void persistsAnAffirmativelyNamedGameTypeButRejectsANegatedInteractionMode() {
        TrackingCatalog catalog = catalog(BggGameType.FAMILY);
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "browse-with-explicit-category",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":4,\"preferenceUpdates\":["
                                + "{\"field\":\"type\",\"value\":\"FAMILY\",\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"interaction\",\"value\":\"COOPERATIVE\",\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT")
                            .contains("\"type\":\"FAMILY\"", "\"interaction\":\"ANY\"");
                    return action(
                            "recommend-explicit-category",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按明确说出的家庭游戏分类筛选，但不会把已经排除的合作模式写回偏好。\","
                                    + "\"selections\":[{\"bggId\":50},{\"bggId\":60}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "想找家庭游戏；合作游戏已经玩腻了，这次换个方向。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.FAMILY);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT",
                "UPDATE_PREFERENCES",
                "SEARCH_BGG_CATALOG",
                "RECOMMEND_GAMES");
    }

    @Test
    void constrainsAnExplicitRecommendationQuantityAtTheSchemaAndActionBoundary() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "Do not ask merely because a useful request is broad or the profile is empty",
                                    "Speak like a decision partner at the table, not a task runner or completion report",
                                    "offer a modest first-person opinion",
                                    "language of their actual group and planned session",
                                    "prefer one plain question about the intended play situation");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.ASK_TOOL);
                    return action(
                            "inspect-three",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\"]}");
                },
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains("\"explicitRecommendationCount\":2");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema())
                            .contains(
                                    "\"selections\":{\"type\":\"array\"",
                                    "\"minItems\":2,\"maxItems\":2");
                    return action(
                            "too-few-selections",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard。\",\"selections\":["
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"internalEvidenceIds\":[\"B60:durationMinutes\"]}]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("SELECTION_COUNT_INVALID");
                    return action(
                            "too-many-selections",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard、Loom City 和 Signal Bazaar。\",\"selections\":["
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                                    + "{\"bggId\":61,\"why\":\"标注时长为 45–60 分钟。\",\"internalEvidenceIds\":[\"B61:durationMinutes\"]},"
                                    + "{\"bggId\":63,\"why\":\"标注时长为 60–75 分钟。\",\"internalEvidenceIds\":[\"B63:durationMinutes\"]}]}" );
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("SELECTION_COUNT_INVALID");
                    return action(
                            "exactly-two-selections",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先把 Glass Orchard 和 Loom City 放在一起看；两张卡都保留了可核对的时长边界。\",\"selections\":["
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"tradeoff\":\"资料没有实际桌感报告。\",\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                                    + "{\"bggId\":61,\"why\":\"标注时长为 45–60 分钟。\",\"tradeoff\":\"资料没有实际桌感报告。\",\"internalEvidenceIds\":[\"B61:durationMinutes\"]}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "今晚请给我两款方向不同的桌游。"),
                "zh-CN");

        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:SELECTION_COUNT_INVALID",
                "REJECTED_ACTION:SELECTION_COUNT_INVALID",
                "RECOMMEND_GAMES");
    }

    @Test
    void ignoresLegacyRangeProseAndKeepsOnlyApplicationDerivedCardFacts() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        String rawWhy = "它支持 2 到 4 人，标注 40–55 分钟；两项边界都可直接核对。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "localized-equivalent-ranges",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的人数与时长边界都能直接核对。\",\"selections\":[{"
                                + "\"bggId\":60,"
                                + "\"why\":\"" + rawWhy + "\","
                                + "\"tradeoff\":\"这些目录数值不能证明实际桌感。\","
                                + "\"internalEvidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}"),
                ignored -> action(
                        "legacy-exact-separators",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的人数与时长边界都能直接核对。\",\"selections\":[{"
                                + "\"bggId\":60,"
                                + "\"why\":\"它支持 2..4 人，标注 40..55 分钟。\","
                                + "\"internalEvidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款人数和时长都清楚的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).allSatisfy(reason -> assertThat(reason.text()).isNotEqualTo(rawWhy));
            assertThat(game.reasons()).extracting(reason -> reason.text())
                    .contains("BGG 机制/类型标签：Pattern Building、Abstract Strategy");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void derivesDurationFitFromTheConfirmedProfileInsteadOfModelProse() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        String rawWhy = "它标注 40 到 55 分钟，低于你确认过的 90 分钟上限。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "relate-candidate-duration-to-profile",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的时长可以直接对照你的上限。\",\"selections\":[{"
                                + "\"bggId\":60,"
                                + "\"why\":\"" + rawWhy + "\","
                                + "\"tradeoff\":\"目录时长不能保证每一桌都按时结束。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}]}"),
                ignored -> action(
                        "legacy-candidate-duration-only",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的时长可以直接核对。\",\"selections\":[{"
                                + "\"bggId\":60,"
                                + "\"why\":\"它标注 40 到 55 分钟。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        new RecommendationProfile(
                                (Integer) null,
                                90,
                                null,
                                BggGameType.ALL,
                                InteractionPreference.ANY),
                        "给我一款 90 分钟内的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).allSatisfy(reason -> assertThat(reason.text()).isNotEqualTo(rawWhy));
            assertThat(game.reasons()).extracting(reason -> reason.text())
                    .contains("BGG 标注最长约 55 分钟，在你的上限内");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void neverLeaksLegacyInternalEvidenceIdsIntoApplicationDerivedCardText() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        String rawMessage = "先看 Glass Orchard；它的结构化卡片事实已经核对。";
        String rawWhy = "Glass Orchard 标注 40..55 分钟（B60:durationMinutes）。";
        String rawTradeoff = "目录时长不能证明实际桌感。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "show-internal-evidence-id",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\",\"selections\":[{"
                                + "\"bggId\":60,"
                                + "\"why\":\"" + rawWhy + "\","
                                + "\"tradeoff\":\"" + rawTradeoff + "\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款时长明确的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(60);
            assertThat(game.reasons()).allSatisfy(reason -> assertThat(reason.text())
                    .doesNotContain("B60:durationMinutes")
                    .isNotEqualTo(rawWhy));
            assertThat(game.tradeoffs()).allSatisfy(tradeoff -> assertThat(tradeoff)
                    .doesNotContain("B60:durationMinutes")
                    .isNotEqualTo(rawTradeoff));
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void letsARefinedRecommendationExplainWhyAnAlreadyVisibleCardWasDropped() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-replacement",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Loom City\"]}"),
                ignored -> action(
                        "recommend-replacement",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 已经看过，这轮改留 Loom City；它的标注时长更接近你刚补充的边界。\","
                                + "\"selections\":[{\"bggId\":61,"
                                + "\"why\":\"它标注 45–60 分钟，边界可以直接与刚才那款对照。\","
                                + "\"tradeoff\":\"60 分钟是目录上限，资料不能保证每桌一定提前结束。\","
                                + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "换一个方向，并告诉我为什么不留刚才那款。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(new BoardGameRecommendationAgent.KnownGame(
                                60, "Glass Orchard", "Glass Orchard")),
                        List.of(60)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).contains("上一轮", "不是被判定更差", "不在本轮重复");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(61);
        assertThat(response.harness().actions()).doesNotContain("REJECTED_ACTION:MESSAGE_NAMES_UNSELECTED_GAME");
    }

    @Test
    void rejectsLegacyModelWrittenCardNarrativeFieldsAndAcceptsIdsOnly() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> unmodifiedAction(
                        "legacy-card-narrative",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 更短。\",\"selections\":[{\"bggId\":60,"
                                + "\"why\":\"它适合先开一局。\","
                                + "\"tradeoff\":\"这只是模型生成的取舍。\","
                                + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("UNEXPECTED_ARGUMENT");
                    return action(
                            "corrected-card",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"这款候选的可核对资料已放在卡片里。\","
                                    + "\"selections\":[{\"bggId\":60}]}"
                    );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款短局，并说明为什么。"),
                "zh-CN");

        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.game().ranking().bggId()).isEqualTo(60));
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:UNEXPECTED_ARGUMENT",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsStringEncodedSelectionsAndAcceptsACompactNativeArray() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "string-encoded-selections",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"selections\":\"[{\\\"bggId\\\":60}]\"}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("SELECTIONS_ARRAY_REQUIRED", "native JSON array");
                    return action(
                            "corrected-native-array",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"候选资料已经核对。\",\"selections\":[{\"bggId\":60}]}"
                    );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款短局并说明取舍。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.game().ranking().bggId()).isEqualTo(60));
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:SELECTIONS_ARRAY_REQUIRED",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsApplicationDerivedCandidateCardsWhenTheShortFrameContainsMarkup() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "先看 **Glass Orchard**：它的时长边界更适合试一局；具体依据和取舍在卡片里。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "grounded-markup",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\",\"selections\":[{\"bggId\":60,"
                                + "\"why\":\"它标注 40–55 分钟，时长边界明确。\","
                                + "\"tradeoff\":\"目录资料不能证明实际桌感或等待时间。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B60:mechanics\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款短局并说清理由。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).extracting(reason -> reason.text())
                    .contains("BGG 机制/类型标签：Pattern Building、Abstract Strategy");
            assertThat(game.tradeoffs()).singleElement().asString()
                    .contains("BGG 标签", "不能证明", "实际互动感", "等待时间");
        });
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsApplicationDerivedCandidateCardsWhenTheShortFrameEndsWithAColon() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "这款游戏符合时长边界，具体依据和取舍如下：";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "grounded-card-connector",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"" + rawMessage + "\",\"selections\":[{\"bggId\":60,"
                                + ""
                                + "\"why\":\"Glass Orchard 的标注时长原值是 40..55。\","
                                + "\"tradeoff\":\"这只能证明标注时长，不能保证每桌都在 55 分钟内结束。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款短局并说清理由。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons()).extracting(reason -> reason.text())
                        .contains("BGG 机制/类型标签：Pattern Building、Abstract Strategy"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsNewCandidateRecommendationsThroughPlainChatEvenWhenTheirIdsAreCited() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "bad-plain-reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"我推荐 Glass Orchard 和 Loom City，它们都很贴近你的需求。\",\"referencedBggIds\":[60,61]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "REPLY_RECOMMENDATION_REQUIRES_CARDS",
                                    "New candidate recommendations must use recommend_games");
                    return action(
                            "recommend-with-cards",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我把两个经过核对、侧重点不同的方向放在卡片里，你可以继续告诉我更喜欢哪一个。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找两款有图案构筑感的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REPLY_RECOMMENDATION_REQUIRES_CARDS", "RECOMMEND_GAMES");
    }

    @Test
    void doesNotRejectANaturalReplyMerelyBecauseItsProseContainsAnObservedTitleSubstring() {
        TrackingCatalog catalog = catalog();
        String rawMessage = "Glass Orchard 这个名字刚好出现在核对结果里；我还没有把它作为新推荐提交。";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "natural-reply-with-observed-title",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"" + rawMessage + "\"}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "先核对这个线索，不要替我选。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(rawMessage);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REPLY_TO_USER");
    }

    @Test
    void derivesCardEvidenceFromVerifiedFactsWithoutMakingTheModelCopyTaxonomy() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                request -> {
                    var finalAction = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(finalAction.inputSchema()).doesNotContain("evidenceTerms");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我先给你一个经过核对的方向，具体资料见卡片。\",\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).doesNotContain("Glass Orchard");
        assertThat(response.games().getFirst().game().details().mechanics()).contains("Pattern Building");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .contains("RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION"));
    }

    @Test
    void publishesAUserQuoteLinkedOnlyToTaxonomyVerifiedForThatCandidate() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                request -> {
                    ToolSpec recommendation = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    JsonNode selectionProperties = schema(recommendation)
                            .at("/properties/selections/items/properties");
                    assertThat(selectionProperties.has("why")).isFalse();
                    assertThat(selectionProperties.has("tradeoff")).isFalse();
                    assertThat(selectionProperties.has("internalEvidenceIds")).isFalse();
                    assertThat(selectionProperties.has("bggId")).isTrue();
                    assertThat(selectionProperties.has("preferenceLink")).isFalse();
                    assertThat(selectionProperties.has("fitClaim")).isFalse();
                    return action(
                            "recommend-with-grounded-link",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我把可核对的对应关系放在卡片里。\","
                                    + "\"selections\":[{\"bggId\":60,\"preferenceLink\":{"
                                    + "\"evidenceId\":\"U1\",\"evidenceQuote\":\"图案构筑\","
                                    + "\"taxonomyTerms\":[\"Pattern Building\"]}}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "今晚想玩图案构筑，但别凭空猜互动感。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(card -> {
            assertThat(card.matches()).allSatisfy(text -> assertThat(text).doesNotContain("你说"));
            assertThat(card.reasons())
                    .filteredOn(reason -> reason.kind() == BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE)
                    .filteredOn(reason -> reason.text().contains("图案构筑"))
                    .singleElement()
                    .satisfies(reason -> {
                        assertThat(reason.text()).contains("图案构筑", "Pattern Building", "匹配线索", "不能证明");
                        assertThat(reason.sourceIndexes()).isEmpty();
                    });
        });
    }

    @Test
    void dropsOnlyAnOptionalPreferenceLinkWhoseTaxonomyWasNotVerifiedForTheCandidate() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "invent-taxonomy",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看卡片。\",\"selections\":[{\"bggId\":60,\"preferenceLink\":{"
                                + "\"evidenceId\":\"U1\",\"evidenceQuote\":\"图案构筑\","
                                + "\"taxonomyTerms\":[\"Worker Placement\"]}}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我想玩图案构筑。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(card ->
                assertThat(card.reasons())
                        .noneMatch(reason -> reason.text().contains("Worker Placement")));
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DROPPED_OPTIONAL_PREFERENCE_LINK:PREFERENCE_LINK_TAXONOMY_NOT_VERIFIED",
                "RECOMMEND_GAMES");
    }

    @Test
    void dropsOnlyAnOptionalPreferenceLinkQuoteThatIsNotInTheCitedUserMessage() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "invent-quote",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看卡片。\",\"selections\":[{\"bggId\":60,\"preferenceLink\":{"
                                + "\"evidenceId\":\"U1\",\"evidenceQuote\":\"资源转换引擎\","
                                + "\"taxonomyTerms\":[\"Pattern Building\"]}}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我想玩图案构筑。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("已按当前条件核对出 1 款候选；卡片中只展示可核对的资料和有出处的信息。");
        assertThat(response.games()).singleElement().satisfies(card ->
                assertThat(card.reasons())
                        .noneMatch(reason -> reason.text().contains("资源转换引擎")));
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DROPPED_OPTIONAL_PREFERENCE_LINK:PREFERENCE_LINK_QUOTE_NOT_GROUNDED",
                "RECOMMEND_GAMES");
    }

    @Test
    void dropsAMalformedOptionalPreferenceLinkWithoutRetryingVerifiedCards() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "incomplete-optional-link",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"这张卡的身份和目录资料已经核对。\",\"selections\":[{\"bggId\":60,"
                                + "\"preferenceLink\":{\"evidenceId\":\"U1\"}}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我想玩图案构筑。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DROPPED_OPTIONAL_PREFERENCE_LINK:REQUIRED_ARGUMENT_MISSING",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsOneModelWrittenQuestionWhenItsOptionalChoicesAreMalformed() {
        String publishedQuestion = "这两种理解会把候选带向不同的互动取舍。你更偏向大家一起笑，还是希望桌上能互相算计？";
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.ASK_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                            .inputSchema())
                            .contains(
                                    "natural locale-matched explanation",
                                    "\"options\":{\"type\":\"array\"",
                                    "\"minItems\":2",
                                    "\"maxItems\":3",
                                    "preferenceUpdates",
                                    "Already-stated direct numeric constraints only");
                    return action(
                            "too-few-options",
                            BoardGameRecommendationAgent.ASK_TOOL,
                            "{\"question\":\"" + publishedQuestion + "\",\"options\":[\"大家一起笑\"]}");
                }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找一款热闹的"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.clarification().field())
                .isEqualTo(BoardGameRecommendationAgent.PreferenceField.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(publishedQuestion);
        assertThat(response.assistantMessage().codePoints()
                        .filter(value -> value == '?' || value == '？')
                        .count())
                .isEqualTo(1);
        assertThat(response.clarification().prompt()).isEqualTo(publishedQuestion);
        assertThat(response.clarification().options()).isEmpty();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "DROPPED_OPTIONAL_CLARIFICATION_OPTIONS:STRING_LIST_INVALID",
                "ASK_USER");
        assertThat(response.harness().catalogCalls()).isZero();
    }

    @Test
    void publishesACompleteClarificationWhenTheRationaleFollowsTheQuestion() {
        String rawQuestion = "你更想转向对抗游戏，还是轻松热闹的派对游戏？这个区别会直接改变候选范围。";
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "question-before-rationale",
                BoardGameRecommendationAgent.ASK_TOOL,
                "{\"question\":\"" + rawQuestion + "\","
                        + "\"options\":[\"对抗游戏\",\"派对游戏\"]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "五个人，合作玩腻了。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.assistantMessage()).isEqualTo(rawQuestion);
        assertThat(response.clarification().prompt()).isEqualTo(rawQuestion);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly("ASK_USER");
    }

    @Test
    void doesNotRequireAPlayerCountWhenTheConversationCanProceedFromContext() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.ASK_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                    .inputSchema())
                    .contains(
                            "natural locale-matched explanation",
                            "\"options\":{\"type\":\"array\"",
                            "preferenceUpdates",
                            "Already-stated direct numeric constraints only");
            return action(
                    "continue-with-context",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我先按你和爸妈一起玩来理解，后面人数有变化直接告诉我就好。\"}");
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "周末想带爸妈玩一局，轻松一点就好。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().players()).isNull();
        assertThat(response.harness().actions()).containsExactly("REPLY_TO_USER");
    }

    @Test
    void recordsTheExplicitWholeGroupAndDurationWithOneAtomicDirectClassification() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            ToolSpec reply = request.tools().stream()
                    .filter(tool -> BoardGameRecommendationAgent.REPLY_TOOL.equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(reply.inputSchema())
                    .contains(
                            "evidenceClassification",
                            "DIRECT",
                            "CONTEXTUAL_COMPLETE_GROUP")
                    .doesNotContain("evidenceStatus", "evidenceReason");
            return action(
                    "record-explicit-whole-group",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"好的，我按五人、九十分钟内继续。\",\"preferenceUpdates\":["
                            + "{\"field\":\"players\",\"value\":5,\"evidence\":\"U1\","
                            + "\"evidenceClassification\":\"DIRECT\"},"
                            + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":90},"
                            + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}" );
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚五个人，有两个第一次玩桌游，最多九十分钟。"),
                "zh-CN");

        assertThat(response.profile().players()).isEqualTo(5);
        assertThat(response.profile().players()).isNotEqualTo(2);
        assertThat(response.profile().maxMinutes()).isEqualTo(90);
        assertThat(response.userModel().hypotheses()).isEmpty();
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "REPLY_TO_USER");
    }

    @Test
    void keepsASubgroupCountOutOfTheHardPlayerProfile() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "keep-subgroup-contextual",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"两位第一次玩的成员不等于整桌人数，我先不锁定硬人数。\","
                        + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "有两个第一次玩桌游的人，整桌人数还没定。"),
                "zh-CN");

        assertThat(response.profile().playerCount()).isNull();
        assertThat(response.userModel().hypotheses()).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.field()).isEqualTo("players");
            assertThat(hypothesis.value()).isEqualTo("2");
        });
        assertThat(response.harness().actions()).containsExactly(
                "RECORD_CONTEXTUAL_PREFERENCE",
                "REPLY_TO_USER");
    }

    @Test
    void inBandClassificationKeepsACompleteGroupCountAsAVisibleReversibleAssumption() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                        "reply-with-contextual-count",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"我先按你和爸妈三个人来理解，人数有变化随时改。\","
                        + "\"preferenceUpdates\":[{\"field\":\"playerCount\","
                        + "\"value\":3,\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "周末想带爸妈玩一局，轻松一点就好。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().players()).isNull();
        assertThat(response.userModel().summary()).contains("可随时更正的语境假设");
        assertThat(response.userModel().hypotheses()).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.field()).isEqualTo("playerCount");
            assertThat(hypothesis.value()).isEqualTo("3");
            assertThat(hypothesis.text()).contains("暂按 3 人理解", "尚未确认为硬条件");
            assertThat(hypothesis.confidence()).isEqualTo("medium");
            assertThat(hypothesis.basedOn()).contains("带爸妈");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RECORD_CONTEXTUAL_PREFERENCE",
                "REPLY_TO_USER");
    }

    @Test
    void treatsAnExplicitNumericRangeAsDirectEvenWhenTheModelMislabelsItContextual() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-with-mislabeled-direct-range",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"我已经按你明确说的 3 到 4 人记录，不把它降成语境猜测。\","
                        + "\"preferenceUpdates\":[{\"field\":\"playerCount\","
                        + "\"value\":{\"minimum\":3,\"maximum\":4},\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们明确是 3 到 4 个人。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().playerCount().minimum()).isEqualTo(3);
        assertThat(response.profile().playerCount().maximum()).isEqualTo(4);
        assertThat(response.userModel().hypotheses()).isEmpty();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "REPLY_TO_USER");
    }

    @Test
    void exposesInBandPreferenceClassificationInEveryActionSchema() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools())
                    .allSatisfy(tool -> {
                        if (!tool.inputSchema().contains("preferenceUpdates")) return;
                        if (BoardGameRecommendationAgent.ASK_TOOL.equals(tool.name())) {
                            assertThat(tool.inputSchema())
                                    .contains("Already-stated direct numeric constraints only")
                                    .doesNotContain("CONTEXTUAL_COMPLETE_GROUP");
                            return;
                        }
                        assertThat(tool.inputSchema())
                                .contains(
                                        "evidenceClassification",
                                        "DIRECT",
                                        "CONTEXTUAL_COMPLETE_GROUP");
                    });
            assertThat(request.messages().getFirst().content())
                    .contains(
                            "Store only explicit numeric/type constraints",
                            "complete-group count",
                            "result count and qualitative taste are not profile values",
                            "later corrections replace earlier values");
            return action(
                    "reply-from-context",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我先按三个人来理解，人数有变化随时告诉我。\","
                        + "\"preferenceUpdates\":[{\"field\":\"playerCount\","
                        + "\"value\":{\"minimum\":3,\"maximum\":3},\"evidence\":\"U1\","
                        + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}" );
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "周末想带爸妈玩一局。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().players()).isNull();
        assertThat(response.userModel().hypotheses()).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.field()).isEqualTo("playerCount");
            assertThat(hypothesis.value()).isEqualTo("3");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RECORD_CONTEXTUAL_PREFERENCE",
                "REPLY_TO_USER");
    }

    @Test
    void ignoresContextualClassificationOutsideTheExactPlayerCountBoundaryWithoutRetryingNaturalReply() {
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "reply-with-exclusion",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"明白，你想换掉合作这个方向；我不会擅自把它等同成某一种固定互动模式。\","
                                + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U1\","
                                + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
                    return action(
                            "reply-without-unsupported-update",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"明白：先排除合作方向，但我不会把这自动等同成某一种固定互动模式。\"}");
                }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "合作游戏已经玩腻了，想换换口味。先别给卡片。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID",
                "REPLY_TO_USER");
    }

    @Test
    void keepsASafeReplyWhenItsOptionalDirectCategoryUpdateIsNotExplicitlyGrounded() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-with-rejected-category",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"明白，这轮先排除已经玩腻的合作方向，不把它反向写成合作偏好。\","
                        + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COOPERATIVE\","
                        + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "合作游戏已经玩腻了，想换换口味。先别给卡片。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT",
                "REPLY_TO_USER");
    }

    @Test
    void appliesDirectFieldsEvenWhenASiblingProposalIsOnlyContextual() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                        "reply-with-mixed-evidence",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"我先按三人理解，并记下了明确的 60 分钟上限。\","
                                + "\"preferenceUpdates\":["
                                + "{\"field\":\"playerCount\",\"value\":{\"minimum\":3,\"maximum\":3},\"evidence\":\"U1\","
                                + "\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"},"
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\","
                                + "\"evidenceClassification\":\"DIRECT\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "周末想带爸妈玩一局，最多 60 分钟。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().players()).isNull();
        assertThat(response.profile().maxMinutes()).isEqualTo(60);
        assertThat(response.userModel().hypotheses()).singleElement()
                .extracting(value -> value.text())
                .asString()
                .contains("暂按 3 人理解");
        assertThat(response.harness().actions()).containsExactly(
                "RECORD_CONTEXTUAL_PREFERENCE",
                "UPDATE_PREFERENCES",
                "REPLY_TO_USER");
    }

    @Test
    void acceptsExplicitEnglishPlayerAndHourQuantitiesAsTypedEvidence() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-with-explicit-quantities",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"],\"preferenceUpdates\":["
                                + "{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\"},"
                                + "{\"field\":\"maxMinutes\",\"value\":90,\"evidence\":\"U1\"}]}"),
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"I kept the explicit group size and time limit while checking these cards.\","
                                + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "We have four players and up to 1.5 hours; show me a couple of pattern games."),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().maxMinutes()).isEqualTo(90);
        assertThat(response.harness().actions()).contains("UPDATE_PREFERENCES", "RECOMMEND_GAMES");
    }

    @Test
    void canContinueAFocusedGameConversationAndGroundAProseReplyInItsVerifiedId() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains("focusedBggId", "60", "它和刚才那款相比互动怎么样", "knownGames")
                            .doesNotContain("Pattern Building", "restoredRunMemory");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.LOOKUP_TOOL);
                    return action(
                            "load-focused-on-demand",
                            BoardGameRecommendationAgent.LOOKUP_TOOL,
                            "{\"bggIds\":[60]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("Pattern Building");
                    return action(
                            "reply-focused",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"就已核对的 BGG 资料看，它以图案构筑为主；这份目录资料不足以证明具体互动强度，所以我不会硬猜。\",\"referencedBggIds\":[60]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "它和刚才那款相比互动怎么样？",
                        List.of(),
                        List.of(new DialogueMessage("user", "它和刚才那款相比互动怎么样？")),
                        60,
                        List.of(new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard")),
                        List.of(60)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).contains("不足以证明", "不会硬猜");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES", "REPLY_TO_USER");
    }

    @Test
    void reusesPersistedVerifiedFactsForAPronounContinuationWithoutRepeatingCatalogReads() {
        TrackingCatalog catalog = catalog();
        Game remembered = catalog.games.get(60);
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().get(1).content())
                    .contains("它主要是什么机制", "restoredRunMemory", "Pattern Building");
            assertThat(request.tools()).extracting(ToolSpec::name)
                    .doesNotContain(BoardGameRecommendationAgent.LOOKUP_TOOL);
            return action(
                    "reply-from-memory",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"上一轮已核验的资料里，它的机制包括图案构筑。\",\"referencedBggIds\":[60]}");
        }));

        var response = agent(model, catalog, noResearch()).conversePersisted(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "它主要是什么机制？",
                        List.of(),
                        List.of(
                                new DialogueMessage("assistant", "可以继续聊玻璃果园。"),
                                new DialogueMessage("user", "它主要是什么机制？")),
                        null,
                        List.of(new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard")),
                        List.of(60),
                        List.of(remembered)),
                "zh-CN",
                null,
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).contains("图案构筑");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(catalog.calls).isZero();
    }

    @Test
    void letsTheAgentChooseAStructuredComparisonWhoseCellsComeOnlyFromCandidateScopedObservations() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                request -> {
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.COMPARE_TOOL);
                    var comparisonTool = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.COMPARE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(comparisonTool.inputSchema())
                            .contains("Unknown attributes remain visibly unknown");
                    assertThat(schema(comparisonTool).at("/properties/subjects/items").has("enum"))
                            .as("an optional display axis must not turn a valid natural comparison into a retry")
                            .isFalse();
                    assertThat(comparisonTool.description())
                            .contains(
                                    "verified conversation candidates",
                                    "complete, concise player-facing comparison",
                                    "The UI will not display a comparison table",
                                    "Never output a Markdown table",
                                    "published exactly as written",
                                    "Persist any explicit current-turn numeric or type correction");
                    assertThat(schema(comparisonTool).at("/properties/message/type").asText()).isEqualTo("string");
                    assertThat(schema(comparisonTool).at("/properties/internalEvidenceIds/items/enum"))
                            .isNotEmpty();
                    assertThat(schema(comparisonTool).at("/properties/preferredBggId/anyOf")).hasSize(2);
                    assertThat(schema(comparisonTool).at("/properties/preferenceUpdates/type").asText())
                            .isEqualTo("array");
                    assertThat(comparisonTool.inputSchema())
                            .contains("\"required\":[\"candidateBggIds\",\"subjects\",\"preferredBggId\",\"message\",\"internalEvidenceIds\"]")
                            .doesNotContain("decisionMode", "decisionEvidenceIds");
                    return action(
                            "compare-restored",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\",\"mechanics\",\"获奖沿革\"],"
                                    + "\"preferredBggId\":60,"
                                    + "\"message\":\"你们想兼顾时间和机制，我会先选 Glass Orchard：它的标注时长更短；两款的机制差异在表里，获奖沿革目前没有可核对资料。\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B60:mechanics\",\"B61:durationMinutes\",\"B61:mechanics\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "把这两款按时长、机制和真实桌感摆在一起比较，资料不足就直说。",
                        List.of(),
                        List.of(new DialogueMessage(
                                "user", "把这两款按时长、机制和真实桌感摆在一起比较，资料不足就直说。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.games()).isEmpty();
        assertThat(response.assistantMessage()).isEqualTo(
                "你们想兼顾时间和机制，我会先选 Glass Orchard：它的标注时长更短；两款的机制差异在表里，获奖沿革目前没有可核对资料。");
        assertThat(response.comparison().candidates())
                .extracting(candidate -> candidate.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.comparison().axes()).extracting(axis -> axis.subject())
                .containsExactly("durationMinutes", "mechanics", "获奖沿革");
        assertThat(response.comparison().axes().get(0).cells())
                .extracting(cell -> cell.observation().value())
                .containsExactly("40..55", "45..60");
        assertThat(response.comparison().axes().get(1).cells())
                .extracting(cell -> cell.observation().value())
                .containsExactly("Pattern Building", "Tile Placement, Open Drafting");
        assertThat(response.comparison().axes().get(2).cells()).allSatisfy(cell -> {
            assertThat(cell.known()).isFalse();
            assertThat(cell.observation()).isNull();
        });
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES", "COMPARE_CANDIDATES");
    }

    @Test
    void titleResolutionToolExplainsThatStandaloneCorrectionsInheritThePendingRole() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            ToolSpec resolve = request.tools().stream()
                    .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(resolve.description()).contains(
                    "COMPARISON_REFERENCE",
                    "never selected",
                    "standalone correction inherits",
                    "never promote it to TARGET_GAME",
                    "still-open goal can continue");
            return action(
                    "continue-comparison-role",
                    BoardGameRecommendationAgent.RESOLVE_TOOL,
                    "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\"}");
        }, ignored -> action(
                "inspect-new-candidates",
                BoardGameRecommendationAgent.SEARCH_TOOL,
                "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "recommend-similar-games",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"按刚才修正的参照游戏，我给你两条不同方向。\",\"selections\":["
                                + "{\"bggId\":60,\"why\":\"两者都有图案构筑标签。\","
                                + "\"tradeoff\":\"标签不能证明实际互动感。\","
                                + "\"internalEvidenceIds\":[\"B60:mechanics\"]},"
                                + "{\"bggId\":61,\"why\":\"两者都有板块放置标签。\","
                                + "\"tradeoff\":\"标签不能证明上手门槛。\","
                                + "\"internalEvidenceIds\":[\"B61:mechanics\"]}]}")));

        var response = agent(model, catalogWithReferenceAndThreeShortGames(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Mosaic Field",
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "我想找和《马赛克花园》机制接近的游戏。"),
                                new DialogueMessage("assistant", "能告诉我它的英文名吗？"),
                                new DialogueMessage("user", "Mosaic Field")),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61)
                .doesNotContain(50);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void requiresAStructuredTerminalActionAfterExternalEvidenceWasRead() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> new Turn(
                        "我已经查完了：两款实际桌感都很好，我会直接选 Glass Orchard。",
                        List.of()),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "cannot be published after external evidence was read",
                                    "compare_candidates",
                                    "Do not perform another read");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.COMPARE_TOOL);
                    return action(
                            "grounded-comparison",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\",\"reportedExperience\"],"
                                    + "\"preferredBggId\":60,"
                                    + "\"message\":\"只看目前能核对的时长，我先选 Glass Orchard；真实桌感没有报告支持，我先不替你猜。\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "这两款实际玩起来怎么选？",
                        List.of(),
                        List.of(new DialogueMessage("user", "这两款实际玩起来怎么选？")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.comparison()).isNotNull();
        assertThat(response.assistantMessage()).isEqualTo(
                "只看目前能核对的时长，我先选 Glass Orchard；真实桌感没有报告支持，我先不替你猜。");
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_UNSTRUCTURED_EVIDENCE_REPLY",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsAnUnstructuredReplyAfterMultiCandidateResearchEvenIfAModelCallsAHiddenTool() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "research-both",
                        BoardGameRecommendationAgent.RESEARCH_TOOL,
                        "{\"bggIds\":[60,61],\"question\":\"reported four-player experience\"}"),
                request -> {
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.COMPARE_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.RESEARCH_TOOL,
                                    BoardGameRecommendationAgent.REPLY_TOOL);
                    return action(
                            "attempt-hidden-reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"两款都很适合新手，我直接替你选。\",\"referencedBggIds\":[60,61]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "ACTION_NOT_AVAILABLE",
                                    "Choose one action from the supplied list");
                    return action(
                            "grounded-comparison",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"reportedExperience\",\"durationMinutes\"],"
                                    + "\"preferredBggId\":null,"
                                    + "\"message\":\"现有资料只够比较标注时长，实际四人桌感还没有报告支持；如果只凭这些信息，我不会硬替你们分胜负。\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}");
                }));

        var response = agent(model, catalog, emptyConfiguredResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人实际玩起来怎么选？",
                        List.of(),
                        List.of(new DialogueMessage("user", "我们四个人实际玩起来怎么选？")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.comparison()).isNotNull();
        assertThat(response.assistantMessage()).isEqualTo(
                "现有资料只够比较标注时长，实际四人桌感还没有报告支持；如果只凭这些信息，我不会硬替你们分胜负。");
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "RESEARCH_GAME_FIT",
                "REJECTED_UNAVAILABLE_ACTION",
                "COMPARE_CANDIDATES");
    }

    @Test
    void researchesEveryVerifiedComparisonCandidateOnceAndPublishesAttributedCellsAndSources() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        AtomicInteger researchCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                researchCalls.incrementAndGet();
                assertThat(request.candidates()).extracting(candidate -> candidate.bggId())
                        .containsExactly(60, 61);
                assertThat(request.question()).contains("four-player", "waiting time", "new players");
                return Optional.of(new Research(
                        List.of(
                                new GameResearch(
                                        60,
                                        List.of(new Observation(
                                                "A four-player report describes visible drafting competition and longer waits while newcomers learn the collection timing.",
                                                List.of(1)))),
                                new GameResearch(
                                        61,
                                        List.of(new Observation(
                                                "A four-player report describes simultaneous choices and short waits, while voting outcomes vary by group.",
                                                List.of(2))))),
                        List.of(
                                new Source(
                                        1,
                                        "Glass Orchard play report",
                                        "https://reports.example.test/glass",
                                        "reports.example.test"),
                                new Source(
                                        2,
                                        "Loom City play report",
                                        "https://reports.example.test/loom",
                                        "reports.example.test"))));
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                throw new AssertionError("known verified games need attributed research, not candidate discovery");
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                request -> {
                    assertThat(request.toolChoice())
                            .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.RESEARCH_TOOL);
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RESEARCH_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .description())
                            .contains("include every compared bggId", "one bounded call");
                    return action(
                            "research-both",
                            BoardGameRecommendationAgent.RESEARCH_TOOL,
                            "{\"bggIds\":[60,61],\"question\":\"four-player interaction, waiting time, and burden for new players\"}");
                },
                request -> {
                    assertThat(request.toolChoice())
                            .isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.COMPARE_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.RESEARCH_TOOL,
                                    BoardGameRecommendationAgent.REPLY_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"attribute\":\"reportedExperience\"",
                                    "\"kind\":\"ATTRIBUTED_REPORT\"",
                                    "Glass Orchard play report",
                                    "Loom City play report");
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.COMPARE_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .description())
                            .contains(
                                    "reportedExperience",
                                    "The UI will not display a comparison table",
                                    "complete, concise player-facing comparison",
                                    "Never output a Markdown table",
                                    "Leave unsupported qualities unknown");
                    return action(
                            "compare-with-reports",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],"
                                    + "\"subjects\":[\"reportedExperience\",\"durationMinutes\",\"replayVariety\"],"
                                    + "\"preferredBggId\":61,"
                                    + "\"message\":\"你们有新手又怕等，我会选 Loom City：玩家报告里它是同时选择、等待更短；但投票结果更吃具体玩家组合，这点可能让你们改选 Glass Orchard。\","
                                    + "\"internalEvidenceIds\":[\"R60:1\",\"R61:1\"]}");
                }));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "结合有来源的玩家报告深入比较这两款的四人互动、等待和新手负担。",
                        List.of(),
                        List.of(new DialogueMessage(
                                "user", "结合有来源的玩家报告深入比较这两款的四人互动、等待和新手负担。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(researchCalls).hasValue(1);
        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(
                "你们有新手又怕等，我会选 Loom City：玩家报告里它是同时选择、等待更短；但投票结果更吃具体玩家组合，这点可能让你们改选 Glass Orchard。");
        assertThat(response.comparison().axes().getFirst().subject()).isEqualTo("reportedExperience");
        assertThat(response.comparison().axes().getFirst().cells()).allSatisfy(cell -> {
            assertThat(cell.known()).isTrue();
            assertThat(cell.observation().attribute()).isEqualTo("reportedExperience");
            assertThat(cell.observation().kind()).isEqualTo(com.rulepilot.recommendation.CandidateObservation.Kind.ATTRIBUTED_REPORT);
        });
        assertThat(response.comparison().axes().getLast().cells()).allSatisfy(cell ->
                assertThat(cell.known()).isFalse());
        assertThat(response.researchSources()).extracting(source -> source.index())
                .containsExactly(1, 2);
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES", "RESEARCH_GAME_FIT", "COMPARE_CANDIDATES");
    }

    @Test
    void keepsVerifiedGameResearchAvailableAfterOneMistakenCandidateDiscovery() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        AtomicInteger discoveryCalls = new AtomicInteger();
        AtomicInteger researchCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                researchCalls.incrementAndGet();
                return Optional.of(new Research(
                        List.of(
                                new GameResearch(60, List.of(new Observation("Sourced report for Glass Orchard.", List.of(1)))),
                                new GameResearch(61, List.of(new Observation("Sourced report for Loom City.", List.of(2))))),
                        List.of(
                                new Source(1, "Report one", "https://reports.example.test/one", "reports.example.test"),
                                new Source(2, "Report two", "https://reports.example.test/two", "reports.example.test"))));
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                return Optional.empty();
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "mistaken-candidate-discovery",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"Glass Orchard and Loom City player reports\"}"),
                request -> {
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.RESEARCH_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.DISCOVER_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"semanticPublicDiscovery\":false",
                                    "\"subjectiveFitResearch\":true");
                    return action(
                            "recover-with-verified-game-research",
                            BoardGameRecommendationAgent.RESEARCH_TOOL,
                            "{\"bggIds\":[60,61],\"question\":\"reported four-player experience\"}");
                },
                ignored -> action(
                        "compare-after-recovery",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[60,61],\"subjects\":[\"reportedExperience\"],"
                                + "\"preferredBggId\":null,"
                                + "\"message\":\"两份玩家报告都给了有用线索，但没有哪一边足够替你们直接拍板；我会先看你们更怕等待，还是更怕结果受桌上气氛影响。\","
                                + "\"internalEvidenceIds\":[\"R60:1\",\"R61:1\"]}")));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "深入比较这两款的玩家报告。",
                        List.of(),
                        List.of(new DialogueMessage("user", "深入比较这两款的玩家报告。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(discoveryCalls).hasValue(1);
        assertThat(researchCalls).hasValue(1);
        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.researchSources()).hasSize(2);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "DISCOVER_CANDIDATES",
                "RESEARCH_GAME_FIT",
                "COMPARE_CANDIDATES");
    }

    @Test
    void persistsAnExplicitFollowUpCorrectionInTheSameComparisonAction() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                request -> {
                    var comparison = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.COMPARE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    String evidenceEnum = schema(comparison)
                            .at("/properties/preferenceUpdates/items/properties/evidence/enum")
                            .toString();
                    assertThat(evidenceEnum).contains("U1", "U2");
                    return action(
                            "three-player-comparison",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"playerCount\",\"durationMinutes\"],"
                                    + "\"preferredBggId\":60,"
                                    + "\"message\":\"改成三个人后，我会先选 Glass Orchard：它支持三人，标注时长也更短；如果你们更在意另一款的机制，再把机制加进比较。\","
                                    + "\"internalEvidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\",\"B61:playerCount\",\"B61:durationMinutes\"],"
                                    + "\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":3,\"evidence\":\"U2\",\"evidenceClassification\":\"DIRECT\"}]}" );
                }));

        String opening = "先给我看看这两款。";
        String correction = "那更适合三个人呢？";
        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        correction,
                        List.of(),
                        List.of(
                                new DialogueMessage("user", opening),
                                new DialogueMessage("assistant", "可以，我们接着比较。"),
                                new DialogueMessage("user", correction)),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(
                "改成三个人后，我会先选 Glass Orchard：它支持三人，标注时长也更短；如果你们更在意另一款的机制，再把机制加进比较。");
        assertThat(response.profile().playerCount().minimum()).isEqualTo(3);
        assertThat(response.profile().playerCount().maximum()).isEqualTo(3);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "UPDATE_PREFERENCES",
                "RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE",
                "COMPARE_CANDIDATES");
    }

    @Test
    void publishesAStructuredChoiceWithoutFreeFormFactualOverreach() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "comparison-with-joint-visible-evidence",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                + "\"preferredBggId\":\"60\","
                                + "\"message\":\"只按时长选，我会拿 Glass Orchard：它标注 40 到 55 分钟，比 Loom City 的 45 到 60 分钟更稳一点。\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "只按时长比较这两款并选一个。",
                        List.of(),
                        List.of(new DialogueMessage("user", "只按时长比较这两款并选一个。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(
                "只按时长选，我会拿 Glass Orchard：它标注 40 到 55 分钟，比 Loom City 的 45 到 60 分钟更稳一点。");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES", "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsMissingComparisonNarrativeAndLetsTheAgentCorrectIt() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "comparison-without-optional-narrative",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                + "\"preferredBggId\":60}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("COMPARISON_MESSAGE_INCOMPLETE");
                    return action(
                            "corrected-comparison",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                    + "\"preferredBggId\":60,"
                                    + "\"message\":\"只按时长选，我会拿 Glass Orchard。\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "这两款先按时长比一下，别因为文案出错把结果丢掉。",
                        List.of(),
                        List.of(new DialogueMessage("user", "这两款先按时长比一下。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.comparison().axes()).hasSize(1);
        assertThat(response.comparison().axes().getFirst().cells()).allSatisfy(cell ->
                assertThat(cell.known()).isTrue());
        assertThat(response.assistantMessage()).isEqualTo("只按时长选，我会拿 Glass Orchard。");
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:COMPARISON_MESSAGE_INCOMPLETE",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsComparisonEvidenceOutsideTheSelectedAxesAndLetsTheAgentCorrectIt() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "comparison-with-overreaching-narrative",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                + "\"preferredBggId\":60,\"message\":\"Pick Glass Orchard for its complexity.\","
                                + "\"internalEvidenceIds\":[\"B60:complexity\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("COMPARISON_MESSAGE_EVIDENCE_OUTSIDE_AXES");
                    return action(
                            "corrected-comparison",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                    + "\"preferredBggId\":60,\"message\":\"Pick Glass Orchard for the shorter listed duration.\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Compare the two candidates on duration.",
                        List.of(),
                        List.of(new DialogueMessage("user", "Compare the two candidates on duration.")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage())
                .isEqualTo("Pick Glass Orchard for the shorter listed duration.");
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:COMPARISON_MESSAGE_EVIDENCE_OUTSIDE_AXES",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsAnUnverifiedComparisonCandidateAndAllowsTheAgentToCorrectItsStructuredAction() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "load-comparison-on-demand",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,61]}"),
                ignored -> action(
                        "compare-unverified",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"candidateBggIds\":[60,999],\"subjects\":[\"durationMinutes\"],"
                                + "\"preferredBggId\":60,\"message\":\"Pick Glass Orchard.\","
                                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("COMPARISON_CANDIDATE_NOT_VERIFIED");
                    return action(
                            "compare-invalid-preference",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                    + "\"preferredBggId\":\"999\",\"message\":\"Pick the unknown game.\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("COMPARISON_PREFERENCE_INVALID");
                    return action(
                            "compare-corrected",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"],"
                                    + "\"preferredBggId\":60,\"message\":\"For the shorter listed duration, pick Glass Orchard.\","
                                    + "\"internalEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Compare the two candidates already shown.",
                        List.of(),
                        List.of(new DialogueMessage("user", "Compare the two candidates already shown.")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "en");

        assertThat(response.comparison().candidates())
                .extracting(candidate -> candidate.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.harness().actions()).containsExactly(
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:COMPARISON_CANDIDATE_NOT_VERIFIED",
                "REJECTED_ACTION:COMPARISON_PREFERENCE_INVALID",
                "COMPARE_CANDIDATES");
    }

    @Test
    void doesNotLoadOrRepublishOldCardsWhenAConversationModelCallFailsBeforeRequestingThem() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(ignored -> {
            throw new IllegalStateException("provider stopped during the follow-up");
        }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "这两款先别丢，我想继续比较。",
                        List.of(),
                        List.of(new DialogueMessage("user", "这两款先别丢，我想继续比较。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).containsExactly(
                "MODEL_CALL_FAILED",
                "UNAVAILABLE:MODEL_CALL_FAILED");
    }

    @Test
    void doesNotRestoreCandidatesExplicitlyExcludedByTheAnotherBatchAction() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().get(1).content())
                    .contains("\"excludedBggIds\":[60,61]")
                    .doesNotContain("\"verifiedGames\"");
            return action(
                    "reply-new-batch",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"明白，这一批不重复前面的候选。\"}");
        }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "换一批",
                        List.of(60, 61),
                        List.of(new DialogueMessage("user", "换一批")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("REPLY_TO_USER");
    }

    @Test
    void doesNotTouchASlowCandidateCatalogForAConversationOnlyTurn() {
        BoardGameRecommendationCatalog slowCatalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(
                    BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(0, List.of());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
                }
                return List.of();
            }

            @Override
            public int gameCount() {
                return 0;
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-after-soft-timeout",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"旧卡片这次没恢复出来，我们仍可以继续聊。\"}")));

        long startedAt = System.nanoTime();
        var response = agent(model, slowCatalog, noResearch(), Duration.ofMillis(200)).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "继续聊刚才那款",
                        List.of(),
                        List.of(new DialogueMessage("user", "继续聊刚才那款")),
                        60,
                        List.of(new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard")),
                        List.of(60)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("REPLY_TO_USER");
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(500);
    }

    @Test
    void returnsExplicitUnavailableInsteadOfRunningAFormerDeterministicFallback() {
        BoardGameRecommendationModel disabled = new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Turn next(Request request) {
                throw new AssertionError("an unconfigured model must not be called");
            }
        };
        TrackingCatalog catalog = new TrackingCatalog();

        var response = agent(disabled, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我推荐一款适合四人的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage()).contains("这轮推荐没有完成", "继续查找已经停止");
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).contains("UNAVAILABLE:MODEL_NOT_CONFIGURED");
        assertThat(catalog.calls).isZero();
    }

    @Test
    void carriesTheAuthenticatedOwnerAcrossTheBoundedModelThread() {
        AtomicReference<String> configuredOwner = new AtomicReference<>();
        AtomicReference<String> invokedOwner = new AtomicReference<>();
        BoardGameRecommendationModel ownerScoped = new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                throw new AssertionError("thread-local startup configuration must not be consulted");
            }

            @Override
            public boolean configured(String ownerUsername) {
                configuredOwner.set(ownerUsername);
                return true;
            }

            @Override
            public Turn next(Request request) {
                throw new AssertionError("thread-local startup configuration must not be invoked");
            }

            @Override
            public Turn next(Request request, String ownerUsername) {
                invokedOwner.set(ownerUsername);
                return action(
                        "reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"先聊聊你今天想玩什么。\",\"referencedBggIds\":[]}");
            }
        };

        var response = agent(ownerScoped, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "你好，我想聊聊桌游设计"),
                "zh-CN",
                "alice");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(configuredOwner).hasValue("alice");
        assertThat(invokedOwner).hasValue("alice");
        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_ASSISTED);
    }

    @Test
    void boundsConversationContextBeforeTheFirstModelTurn() {
        List<DialogueMessage> transcript = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            transcript.add(new DialogueMessage(index % 2 == 0 ? "user" : "assistant", "turn-" + index + "-" + "x".repeat(200)));
        }
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            String input = request.messages().get(1).content();
            assertThat(input).doesNotContain("turn-0-").contains("turn-8-", "turn-19-");
            assertThat(input.length()).isLessThan(5_000);
            assertThat(request.maxOutputTokens()).isEqualTo(600);
            return action(
                    "reply",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我接着刚才的话说。\",\"referencedBggIds\":[]}");
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "",
                        List.of(),
                        transcript,
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
    }

    @Test
    void rejectsAnIdenticalRepeatedActionWithoutPayingForTheCatalogTwice() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "lookup-1",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[999]}"),
                ignored -> action(
                        "lookup-2",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[999]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("REPEATED_ACTION");
                    return action(
                            "reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"这个线索暂时没查到，我先不乱猜。你愿意换一种描述吗？\",\"referencedBggIds\":[]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "继续看看刚才那款",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(999)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).contains("REJECTED_REPEATED_ACTION");
    }

    @Test
    void keepsIdentityClarificationAvailableAfterAnUnresolvedTitleAndARepeatedModelAction() {
        TrackingCatalog catalog = new TrackingCatalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "resolve-unmatched-title",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"马赛克花园\",\"purpose\":\"COMPARISON_REFERENCE\"}"),
                request -> {
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(
                                    BoardGameRecommendationAgent.ASK_TOOL,
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.RESOLVE_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.LOOKUP_TOOL);
                    return action(
                            "repeat-unmatched-title",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"马赛克花园\",\"purpose\":\"COMPARISON_REFERENCE\"}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("REPEATED_ACTION");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .contains(BoardGameRecommendationAgent.ASK_TOOL);
                    return action(
                            "ask-for-identity-detail",
                            BoardGameRecommendationAgent.ASK_TOOL,
                            "{\"question\":\"这个中文名没有唯一匹配到条目；能告诉我盒面上的英文名吗？\"}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我想找和《马赛克花园》机制接近的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.assistantMessage()).contains("英文名");
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE",
                "REJECTED_REPEATED_ACTION",
                "ASK_USER");
    }

    @Test
    void preservesTheRealInvalidJsonFeedbackWhenAProviderRepeatsTheMalformedAction() {
        String malformedArguments = "{\"titles\":[\"Glass Orchard\"]";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "malformed-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        malformedArguments),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("INVALID_JSON", "escape string content correctly");
                    return action(
                            "malformed-two",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            malformedArguments);
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("INVALID_JSON", "escape string content correctly")
                            .doesNotContain("REPEATED_ACTION");
                    return action(
                            "recover",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"刚才的动作参数没有完整编码；这次没有把错误结果当成候选。你可以直接重试。\"}");
                }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款经典游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:INVALID_JSON",
                "REJECTED_ACTION:INVALID_JSON",
                "REPLY_TO_USER");
    }

    @Test
    void retiresEachBatchedCatalogReadAfterOneAttemptSoTheAgentMustAdvance() {
        TrackingCatalog catalog = new TrackingCatalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-empty",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Unknown Design\"]}"),
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.BROWSE_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.ASK_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains("one bounded title-inspection attempt", "do not inspect titles again");
                    return action(
                            "browse-empty",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            "{\"limit\":8}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .doesNotContain(
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.ASK_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains("one bounded catalog browse", "do not browse again");
                    return action(
                            "reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"这些线索暂时没有核对出可靠候选，我先不乱猜。你愿意补充一种最在意的机制吗？\",\"referencedBggIds\":[]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找机制相近但名字记不清的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().catalogCalls()).isEqualTo(3);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "SEARCH_BGG_CATALOG",
                "REPLY_TO_USER");
    }

    @Test
    void completesAConcreteRecommendationInTwoModelTurnsWithAtomicPreferences() {
        TrackingCatalog catalog = catalog(BggGameType.STRATEGY);
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains("四个人", "科幻主题", "德式重策");
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "Choose a read only when the current turn actually needs information outside the conversation",
                                    "Public candidate discovery and verified-game research are different capabilities",
                                    "never use discovery to investigate a game already named or verified",
                                    "A catalog browse is only a broad exploration or a filter over persisted numeric/type constraints",
                                    "Avoid repeated reads");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .as("catalog filtering must be usable on the first turn for explicit hard constraints")
                            .contains(BoardGameRecommendationAgent.BROWSE_TOOL);
                    return action(
                            "inspect",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                    + "\"preferenceUpdates\":["
                                    + "{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\"},"
                                    + "{\"field\":\"type\",\"value\":\"STRATEGY\",\"evidence\":\"U1\"}]} ");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("Glass Orchard", "Loom City", "Pattern Building", "Tile Placement");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按四人科幻德式重策这个方向，我先给你两个经过核对、侧重点不同的候选。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "你好，我们现在四个人，想玩点科幻主题的德式重策"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.profile().type()).isEqualTo(BggGameType.STRATEGY);
        assertThat(response.profile().maxWeight()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "UPDATE_PREFERENCES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void stopsAfterTheBoundedNumberOfObservationDependentModelTurns() {
        TrackingCatalog catalog = new TrackingCatalog();
        List<Function<Request, Turn>> turns = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> (Function<Request, Turn>) request -> {
                    if (index == 0) {
                        assertThat(request.messages().get(1).content())
                                .contains("\"semanticPublicDiscovery\":false", "\"maximumActionCalls\":6");
                    }
                    if (index == 5) {
                        assertThat(request.messages().getLast().content())
                                .contains("\"remainingModelCalls\":1", "\"remainingActionCalls\":1");
                    }
                    return action(
                            "lookup-" + index,
                            BoardGameRecommendationAgent.LOOKUP_TOOL,
                            "{\"bggIds\":[" + (index + 1) + "]}");
                })
                .toList();
        ScriptedModel model = new ScriptedModel(turns);

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "继续核对这些候选",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(1, 2, 3, 4, 5, 6)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.harness().modelCalls()).isEqualTo(6);
        assertThat(response.harness().catalogCalls()).isEqualTo(6);
        assertThat(response.harness().actions()).contains("REACT_BUDGET_EXHAUSTED");
    }

    @Test
    void reportsAnHonestUnavailableTurnWhenRepeatedInvalidActionsExhaustTheBudget() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        List<Function<Request, Turn>> turns = new ArrayList<>();
        turns.add(ignored -> action(
                "rehydrate-shown",
                BoardGameRecommendationAgent.LOOKUP_TOOL,
                "{\"bggIds\":[60,61]}"));
        java.util.stream.IntStream.range(1, 6).forEach(index -> turns.add(ignored -> action(
                "invalid-final-" + index,
                BoardGameRecommendationAgent.RECOMMEND_TOOL,
                "{\"message\":\"我继续比较。\",\"selections\":[{\"bggId\":60}]}")));

        var response = agent(new ScriptedModel(turns), catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "继续比较刚才的 Glass Orchard 和 Loom City，别丢掉已有结果",
                        List.of(),
                        List.of(),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.games()).isEmpty();
        assertThat(response.assistantMessage()).contains("没有完成", "可以直接重试");
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions())
                .contains("REACT_BUDGET_EXHAUSTED", "UNAVAILABLE:BUDGET_EXHAUSTED");
    }

    @Test
    void neverConvertsVerifiedLeadsIntoRecommendationsWhenTheAgentDecisionFails() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> {
                    throw new IllegalStateException("provider stopped after retrieval");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐两款适合今晚的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).contains("MODEL_CALL_FAILED", "UNAVAILABLE:MODEL_CALL_FAILED");
    }

    @Test
    void stopsAProviderCallAtTheConfiguredWholeRunDeadline() {
        BoardGameRecommendationModel blocking = new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
                }
                throw new IllegalStateException("interrupted after deadline");
            }
        };

        long startedAt = System.nanoTime();
        var response = agent(blocking, new TrackingCatalog(), noResearch(), Duration.ofMillis(40)).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.harness().actions())
                .containsExactly("RUN_DEADLINE_EXCEEDED", "UNAVAILABLE:RUN_DEADLINE_EXCEEDED");
        assertThat(response.harness().totalElapsedMs()).isLessThan(1_000);
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(1_000);
    }

    @Test
    void neverPublishesUnstructuredProviderProseForAPlayerNamedTarget() {
        Game target = game(
                50,
                "Mosaic Field",
                45,
                List.of("Abstract Strategy"),
                List.of("Pattern Building", "Tile Placement"));
        TrackingCatalog catalog = new TrackingCatalog(
                Map.of(50, target),
                Map.of("Mosaic Field", 50));
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
                    return new Turn("I found Mosaic Field. You can continue to its rulebook.", List.of());
                },
                request -> {
                    assertThat(request.messages()).anySatisfy(message -> assertThat(message.content())
                            .contains("unstructured prose cannot be published", "resolve_bgg_game"));
                    return action(
                            "resolve-after-provider-ignored-required-action",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"TARGET_GAME\"}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Find Mosaic Field so I can open its rulebook and guide."),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo("The game you named has been verified; its card shows only checkable data and attributed information.");
        assertThat(response.assistantMessage()).doesNotContain("I found Mosaic Field");
        assertThat(response.games()).extracting(entry -> entry.game().ranking().bggId()).containsExactly(50);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_UNSTRUCTURED_REPLY",
                "RESOLVE_BGG_REFERENCE",
                "RECOMMEND_GAMES");
    }

    @Test
    void neverPublishesAToolPayloadWhenTheProviderReportsAnOutputLimit() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> new Turn(
                "",
                List.of(new ToolCall(
                        "truncated-reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"This looks suitable because the interaction is\"}")),
                CompletionStatus.OUTPUT_LIMIT)));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "Tell me which one fits."),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage()).doesNotContain("interaction is");
        assertThat(response.harness().actions()).containsExactly(
                "MODEL_OUTPUT_TRUNCATED", "UNAVAILABLE:MODEL_OUTPUT_TRUNCATED");
    }

    @Test
    void currentChineseTurnOverridesAnEarlierEnglishConversationForPromptAndSafeFailureCopy() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().getLast().content())
                    .contains(
                            "\"locale\":\"zh-CN\"",
                            "Which candidate works for three players?",
                            "I kept the player count and the verified candidates.",
                            "现在请用中文继续比较。")
                    .containsSubsequence(
                            "Which candidate works for three players?",
                            "I kept the player count and the verified candidates.",
                            "现在请用中文继续比较。");
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "truncated-current-turn",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"This stale English payload must never be shown\"}")),
                    CompletionStatus.OUTPUT_LIMIT);
        }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "现在请用中文继续比较。",
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "Which candidate works for three players?"),
                                new DialogueMessage("assistant", "I kept the player count and the verified candidates."),
                                new DialogueMessage("user", "现在请用中文继续比较。")),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage())
                .contains("这轮推荐没有完成", "继续查找已经停止", "可以直接重试")
                .doesNotContain("stale English payload");
        assertThat(response.harness().actions()).containsExactly(
                "MODEL_OUTPUT_TRUNCATED", "UNAVAILABLE:MODEL_OUTPUT_TRUNCATED");
    }

    @Test
    void preservesNaturalReplyFormattingWithoutAStyleRepairTurn() {
        String rawReply = "**Result:** the available facts do not establish that difference.";
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "natural-reply",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"" + rawReply + "\"}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "What is the deciding difference?"),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(rawReply);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly("REPLY_TO_USER");
    }

    private BoardGameRecommendationAgent agent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationCatalog catalog,
            BoardGameRecommendationWebResearch research) {
        return agent(model, catalog, research, Duration.ofSeconds(20));
    }

    private BoardGameRecommendationAgent agent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationCatalog catalog,
            BoardGameRecommendationWebResearch research,
            Duration timeout) {
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), timeout);
        return new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());
    }

    private static String threeSelectionRecommendation(String firstWhy) {
        return "{\"message\":\"这三张卡都保留了可核对的时长。\",\"selections\":["
                + "{\"bggId\":60,"
                + "\"why\":\"" + firstWhy + "\","
                + "\"tradeoff\":\"目录时长不能证明实际桌感。\","
                + "\"internalEvidenceIds\":[\"B60:durationMinutes\"]},"
                + "{\"bggId\":61,"
                + "\"why\":\"Loom City 标注 45..60 分钟。\","
                + "\"tradeoff\":\"目录时长不能证明实际桌感。\","
                + "\"internalEvidenceIds\":[\"B61:durationMinutes\"]},"
                + "{\"bggId\":63,"
                + "\"why\":\"Signal Bazaar 标注 60..75 分钟。\","
                + "\"tradeoff\":\"目录时长不能证明实际桌感。\","
                + "\"internalEvidenceIds\":[\"B63:durationMinutes\"]}]}";
    }

    private static String recommendationArgumentsFromCurrentSchema(Request request) {
        ToolSpec recommendation = request.tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow();
        JsonNode ids = schema(recommendation)
                .at("/properties/selections/items/properties/bggId/enum");
        var arguments = new ObjectMapper().createObjectNode();
        var selections = arguments.putArray("selections");
        ids.forEach(id -> selections.addObject().put("bggId", id.asInt()));
        return arguments.toString();
    }

    private static Turn action(String id, String name, String arguments) {
        try {
            JsonNode root = new ObjectMapper().readTree(arguments);
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                if (Set.of(
                                BoardGameRecommendationAgent.SEARCH_TOOL,
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                BoardGameRecommendationAgent.DISCOVER_TOOL)
                        .contains(name) && !object.has("preferenceUpdates")) {
                    object.putArray("preferenceUpdates");
                }
                if (Set.of(
                                BoardGameRecommendationAgent.SEARCH_TOOL,
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                BoardGameRecommendationAgent.DISCOVER_TOOL)
                        .contains(name) && !object.has("contextualGroup")) {
                    object.putNull("contextualGroup");
                }
                if (BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(name)
                        && object.path("selections").isArray()) {
                    object.remove("message");
                    for (JsonNode selection : object.path("selections")) {
                        if (selection instanceof com.fasterxml.jackson.databind.node.ObjectNode item) {
                            item.remove(List.of("why", "tradeoff", "internalEvidenceIds"));
                        }
                    }
                }
                if (BoardGameRecommendationAgent.RESOLVE_TOOL.equals(name)) {
                    object.remove("message");
                }
                arguments = object.toString();
            }
        } catch (Exception ignored) {
            // Invalid-JSON scenarios must still reach the production parser unchanged.
        }
        return new Turn("", List.of(new ToolCall(id, name, arguments)));
    }

    private static Turn unmodifiedAction(String id, String name, String arguments) {
        return new Turn("", List.of(new ToolCall(id, name, arguments)));
    }

    private BoardGameRecommendationWebResearch noResearch() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }
        };
    }

    private BoardGameRecommendationWebResearch emptyConfiguredResearch() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }
        };
    }

    private static int requestCharacters(Request request) {
        return request.messages().stream().mapToInt(message -> message.content().length()).sum()
                + request.tools().stream()
                        .mapToInt(tool -> tool.name().length()
                                + tool.description().length()
                                + tool.inputSchema().length())
                        .sum();
    }

    private TrackingCatalog catalog() {
        return catalog(List.of());
    }

    private TrackingCatalog catalog(BggGameType rankedType) {
        return catalog(List.of(rankedType));
    }

    private TrackingCatalog catalog(List<BggGameType> rankedTypes) {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(50, game(50, "Mosaic Field", 45, List.of("Abstract Strategy"), List.of("Pattern Building", "Tile Placement"), rankedTypes));
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building"), rankedTypes));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting"), rankedTypes));
        games.put(62, game(62, "Long Mosaic", 150, List.of("Abstract Strategy"), List.of("Pattern Building"), rankedTypes));
        return new TrackingCatalog(games, Map.of(
                "Mosaic Field", 50,
                "Glass Orchard", 60,
                "Loom City", 61,
                "Long Mosaic", 62));
    }

    private TrackingCatalog catalogWithThreeShortGames() {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting")));
        games.put(63, game(63, "Signal Bazaar", 75, List.of("Negotiation"), List.of("Trading", "Bluffing")));
        return new TrackingCatalog(games, Map.of(
                "Glass Orchard", 60,
                "Loom City", 61,
                "Signal Bazaar", 63));
    }

    private TrackingCatalog catalogWithReferenceAndThreeShortGames() {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(50, game(50, "Mosaic Field", 50, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting")));
        games.put(63, game(63, "Signal Bazaar", 75, List.of("Negotiation"), List.of("Trading", "Bluffing")));
        return new TrackingCatalog(games, Map.of(
                "Mosaic Field", 50,
                "Glass Orchard", 60,
                "Loom City", 61,
                "Signal Bazaar", 63));
    }

    private TrackingCatalog catalogWithTwoShortGames() {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting")));
        return new TrackingCatalog(games, Map.of(
                "Glass Orchard", 60,
                "Loom City", 61));
    }

    private TrackingCatalog catalogWithSixShortGames() {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting")));
        games.put(63, game(63, "Signal Bazaar", 75, List.of("Negotiation"), List.of("Trading", "Bluffing")));
        games.put(64, game(64, "Paper Harbor", 40, List.of("Family"), List.of("Set Collection")));
        games.put(65, game(65, "Quiet Comet", 50, List.of("Thematic"), List.of("Push Your Luck")));
        games.put(66, game(66, "Copper Parade", 45, List.of("Party Game"), List.of("Voting")));
        return new TrackingCatalog(games, Map.of(
                "Glass Orchard", 60,
                "Loom City", 61,
                "Signal Bazaar", 63,
                "Paper Harbor", 64,
                "Quiet Comet", 65,
                "Copper Parade", 66));
    }

    private static Game game(
            int id,
            String name,
            int maximumMinutes,
            List<String> categories,
            List<String> mechanics) {
        return game(id, name, maximumMinutes, categories, mechanics, List.of());
    }

    private static Game game(
            int id,
            String name,
            int maximumMinutes,
            List<String> categories,
            List<String> mechanics,
            List<BggGameType> rankedTypes) {
        return new Game(
                new Ranking(
                        id,
                        name,
                        2024,
                        id,
                        new BigDecimal("7.5"),
                        new BigDecimal("7.8"),
                        1_000,
                        rankedTypes),
                new Details(
                        name,
                        "",
                        "",
                        2,
                        4,
                        maximumMinutes,
                        new BigDecimal("2.4"),
                        categories,
                        mechanics,
                        Math.max(20, maximumMinutes - 15),
                        maximumMinutes,
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

    private static Game gameWithDescription(int id, String name, String description) {
        Game base = game(
                id,
                name,
                60,
                List.of("Thematic"),
                List.of("Hand Management"),
                List.of(BggGameType.THEMATIC));
        Details details = base.details();
        return new Game(
                base.ranking(),
                new Details(
                        details.name(),
                        details.officialChineseName(),
                        details.thumbnailUrl(),
                        details.minPlayers(),
                        details.maxPlayers(),
                        details.playingTimeMinutes(),
                        details.averageWeight(),
                        details.categories(),
                        details.mechanics(),
                        details.minimumPlayTimeMinutes(),
                        details.maximumPlayTimeMinutes(),
                        details.minimumAge(),
                        details.suggestedMinimumAge(),
                        details.bestWith(),
                        details.recommendedWith(),
                        details.languageDependenceLevel(),
                        details.weightVotes(),
                        details.families(),
                        details.designers(),
                        details.publishers(),
                        description,
                        ""));
    }

    private static JsonNode schema(ToolSpec tool) {
        try {
            return new ObjectMapper().readTree(tool.inputSchema());
        } catch (Exception exception) {
            throw new AssertionError("tool schema must be valid JSON", exception);
        }
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static final class ScriptedModel implements BoardGameRecommendationModel {
        private final List<Function<Request, Turn>> turns;
        private final List<Function<NaturalReplyRequest, NaturalReply>> naturalReplies;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger naturalReplyCalls = new AtomicInteger();

        private ScriptedModel(List<Function<Request, Turn>> turns) {
            this(turns, List.of());
        }

        private ScriptedModel(
                List<Function<Request, Turn>> turns,
                List<Function<NaturalReplyRequest, NaturalReply>> naturalReplies) {
            this.turns = List.copyOf(turns);
            this.naturalReplies = List.copyOf(naturalReplies);
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Turn next(Request request) {
            int index = calls.getAndIncrement();
            if (index >= turns.size()) {
                String latest = request.messages().isEmpty()
                        ? "<no messages>"
                        : request.messages().getLast().content();
                throw new AssertionError("unexpected model turn " + index + "; latest=" + latest);
            }
            return turns.get(index).apply(request);
        }

        @Override
        public NaturalReply streamNaturalReply(
                NaturalReplyRequest request,
                String ownerUsername,
                java.util.function.Consumer<String> accumulatedTextListener) {
            int index = naturalReplyCalls.getAndIncrement();
            if (index >= naturalReplies.size()) throw new AssertionError("unexpected natural reply " + index);
            NaturalReply reply = naturalReplies.get(index).apply(request);
            accumulatedTextListener.accept(reply.text());
            return reply;
        }

    }

    private static final class TrackingCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games;
        private final Map<String, Integer> names;
        private int calls;
        private int maximumRequested;
        private String lastResolvedTitle;

        private TrackingCatalog() {
            this(Map.of(), Map.of());
        }

        private TrackingCatalog(Map<Integer, Game> games, Map<String, Integer> names) {
            this.games = Map.copyOf(games);
            this.names = Map.copyOf(names);
        }

        @Override
        public CandidateSet findCandidates(
                BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            calls++;
            maximumRequested = maximum;
            return new CandidateSet(
                    games.size(),
                    games.values().stream().limit(maximum).toList());
        }

        @Override
        public List<Ranking> searchByNames(List<String> titles) {
            calls++;
            return titles.stream()
                    .map(names::get)
                    .filter(java.util.Objects::nonNull)
                    .map(games::get)
                    .map(Game::ranking)
                    .toList();
        }

        @Override
        public List<Game> resolveReferenceTitle(String title) {
            calls++;
            lastResolvedTitle = title;
            Integer id = names.get(title);
            return id == null ? List.of() : List.of(games.get(id));
        }

        @Override
        public List<Game> resolveLocalReferenceTitle(String title) {
            calls++;
            lastResolvedTitle = title;
            Integer id = names.get(title);
            return id == null ? List.of() : List.of(games.get(id));
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            calls++;
            return bggIds.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }
    }
}
