package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
import com.rulepilot.recommendation.RecommendationConversationInputException;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationAgentTest {

    @Test
    void returnsAPlayerNamedTargetAsASelectableRecommendationCard() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains(
                                    "TARGET_GAME",
                                    "COMPARISON_REFERENCE",
                                    "DISCUSSION_SUBJECT",
                                    "IDENTITY_ONLY");
                    return action(
                            "resolve-target",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"TARGET_GAME\"}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .containsExactly(BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"targetGameBggIds\":[50]",
                                    "\"recommendableBggIds\":[50]")
                            .doesNotContain("\"comparisonReferenceBggIds\":[50]");
                    return action(
                            "recommend-target",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"就是你指定的这款；信息已经核对，可以继续为它找规则书。\","
                                    + "\"selections\":[{\"bggId\":50}]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我想玩 Mosaic Field",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(50)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(50);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
        assertThat(catalog.calls).isEqualTo(1);
    }

    @Test
    void letsTheModelChooseFiveResultsWhenTheConversationAsksForFive() {
        TrackingCatalog catalog = catalogWithSixShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "Choose the result count from the conversation",
                                    "never pad a unique relationship");
                    return action(
                            "inspect-six",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\",\"Paper Harbor\",\"Quiet Comet\",\"Copper Parade\"]}");
                },
                request -> {
                    var recommendation = request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(recommendation.description())
                            .contains("Honor an explicit requested quantity");
                    assertThat(recommendation.inputSchema())
                            .contains("\"maxItems\":5")
                            .doesNotContain("\"maxItems\":8")
                            .doesNotContain("\"maxItems\":3");
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
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61, 63, 64, 65);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME", "LOOKUP_BGG_CANDIDATES", "RECOMMEND_GAMES");
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
                "zh-CN");

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
    void canRecoverWithPlainConversationInsteadOfBeingForcedToEmitUnwantedCards() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "A recommendation card is an application action",
                                    "do not emit unwanted cards merely because a slate exists");
                    return action(
                            "unnecessary-inspection",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\",\"Signal Bazaar\"]}");
                },
                request -> {
                    assertThat(request.tools()).extracting(tool -> tool.name())
                            .contains(
                                    BoardGameRecommendationAgent.REPLY_TOOL,
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.RECOMMEND_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.ASK_TOOL,
                                    BoardGameRecommendationAgent.RESOLVE_TOOL);
                    return action(
                            "recover-with-conversation",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"不客气，先停在这里。等你想继续挑时，我会接着刚才的上下文聊。\","
                                    + "\"referencedBggIds\":[]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "谢谢，先不用再推荐了"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.games()).isEmpty();
        assertThat(response.assistantMessage()).contains("先停在这里");
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME", "LOOKUP_BGG_CANDIDATES", "REPLY_TO_USER");
    }

    @Test
    void treatsAStandaloneOriginalTitleAsContinuationAndUsesObservedMechanics() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "Games like X",
                                    "per-turn comparison goal",
                                    "late correction changes the profile",
                                    "recomputes recommendable IDs");
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
        assertThat(response.assistantMessage()).contains("不是换了话题", "图案构筑");
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
                                    "\"subjectiveFitResearch\":true");
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
                                    "\"subjectiveFitResearch\":false")
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
        assertThat(requestCharacters).allSatisfy(size -> assertThat(size).isLessThan(16_000));
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
                            .contains("latest user message", "later explicit correction replaces currentProfile");
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
                                + "\"evidence\":\"U1\",\"evidenceStatus\":\"DIRECT\",\"evidenceReason\":\"DIRECT\"}]}"),
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
                                + "\"evidence\":\"U1\",\"evidenceStatus\":\"DIRECT\",\"evidenceReason\":\"DIRECT\"}]}"),
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
                .isEqualTo("recommendation-agent-v2-grounded-decisions");
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
                                    "\"field\":{\"type\":\"string\",\"enum\":[\"players\"");
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
                            .contains("\"enum\":[\"durationMinutes\"]");
                    return action(
                            "honest-zero-match",
                            BoardGameRecommendationAgent.NO_MATCH_TOOL,
                            "{\"relaxSubject\":\"durationMinutes\"}");
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
        assertThat(response.assistantMessage()).contains("没有一款", "最小的可行调整", "不会替你自动放宽");
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
    void preservesPlayerAuthoredWhitespaceAndCountsUnicodeCodePointsAtTheInputBoundary() {
        String accepted = "😀".repeat(495) + "  A\n中";
        assertThat(accepted.codePointCount(0, accepted.length())).isEqualTo(500);
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
        assertThatThrownBy(() -> agent(model, new TrackingCatalog(), noResearch()).converse(
                        new ConversationRequest(RecommendationProfile.empty(), "😀".repeat(501)),
                        "zh-CN"))
                .isInstanceOfSatisfying(RecommendationConversationInputException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(RecommendationConversationInputException.Code.MESSAGE_TOO_LONG);
                    assertThat(failure.limit()).isEqualTo(500);
                    assertThat(failure.actual()).isEqualTo(501);
                });
    }

    @Test
    void keepsACompleteAssistantTurnBeyondThePlayerInputBoundary() {
        String assistantTurn = "答".repeat(800);
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
    void explicitlyClearsNumericConstraintsWithoutErasingUnmentionedPreferences() {
        RecommendationProfile current = new RecommendationProfile(
                4, 90, new BigDecimal("3.2"), BggGameType.STRATEGY, InteractionPreference.COMPETITIVE);
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.REPLY_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema())
                    .contains("null value clears an explicit limit", "{\"type\":\"null\"}");
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
    void doesNotPersistAnUnconfirmedInteractionGuessFromAClarificationAction() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "ask-with-unconfirmed-mode",
                BoardGameRecommendationAgent.ASK_TOOL,
                "{\"question\":\"这两种方向会改变候选集合。你更偏短局还是长局？\","
                        + "\"options\":[\"偏短局\",\"偏长局\"],"
                        + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COOPERATIVE\","
                        + "\"evidence\":\"U1\",\"evidenceStatus\":\"CONTEXTUAL\","
                        + "\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找一种围着壁炉讲秘密的感觉"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.clarification().options())
                .extracting(BoardGameRecommendationAgent.ClarificationOption::value)
                .containsExactly("偏短局", "偏长局");
        assertThat(response.harness().actions())
                .containsExactly("IGNORED_CLARIFICATION_PREFERENCE_UPDATES", "ASK_USER");
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
                    assertThat(finalAction.inputSchema()).contains(
                            "preferenceUpdates",
                            "evidence",
                            "COMPETITIVE",
                            "A negated mode proves no other enum",
                            "Qualitative tastes");
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
    void preservesGroundedNaturalCandidateExplanationFromTheRawActionToTheVisibleCard() {
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
                                + "\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"它标注 40..55 分钟、支持 2..4 人；\\n这些原值让本次试选的人数与时间边界清楚可核对。\","
                                + "\"tradeoff\":\"资料只证明支持 2–4 人，不能据此断言所有人数下的互动强度一致。\","
                                + "\"evidenceIds\":[\"B60:durationMinutes\",\"B60:playerCount\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .isEqualTo(rawMessage);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons()).first().satisfies(reason ->
                    assertThat(reason.text()).isEqualTo(rawWhy));
            assertThat(game.tradeoffs())
                    .containsExactly("资料只证明支持 2–4 人，不能据此断言所有人数下的互动强度一致。");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsExactly(
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "RECOMMEND_GAMES");
    }

    @Test
    void treatsEmptyOptionalPreferenceUpdatesAsNoopAndRepairsQuotedOrParaphrasedNarrativeFields() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search-with-empty-optional-updates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],\"preferenceUpdates\":[]}"),
                ignored -> action(
                        "quoted-selections-array",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看 Glass Orchard。\",\"selections\":\"[{\\\"bggId\\\":60}]\"}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("SELECTIONS_ARRAY_REQUIRED", "native JSON array");
                    return action(
                            "paraphrased-duration-hides-source-value",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard。\",\"selections\":[{\"bggId\":60,"
                                    + "\"narrativeMode\":\"OBSERVED_ONLY\","
                                    + "\"why\":\"它标注四十到五十五分钟，时间边界适合先核对。\","
                                    + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}" );
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("CANDIDATE_NARRATIVE_EVIDENCE_VALUE_NOT_VISIBLE");
                    return action(
                            "taxonomy-cannot-ground-observed-why",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard。\",\"selections\":[{\"bggId\":60,"
                                    + "\"narrativeMode\":\"OBSERVED_ONLY\","
                                    + "\"why\":\"它的机制标签原值是 Pattern Building；这个标签只提供待核对的方向。\","
                                    + "\"evidenceIds\":[\"B60:mechanics\"]}]}" );
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("CANDIDATE_NARRATIVE_EVIDENCE_NOT_DIRECT_FIT");
                    return action(
                            "literal-taxonomy-visible",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard。\",\"selections\":[{\"bggId\":60,"
                                    + "\"narrativeMode\":\"OBSERVED_ONLY\","
                                    + "\"why\":\"它的标注时长原值是 40..55；这让时间边界可以直接核对。\","
                                    + "\"tradeoff\":\"当前资料没有实际游玩报告，不能判断你期待的桌感。\","
                                    + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一个图案构筑方向。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text())
                        .isEqualTo("它的标注时长原值是 40..55；这让时间边界可以直接核对。"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:SELECTIONS_ARRAY_REQUIRED",
                "REJECTED_ACTION:CANDIDATE_NARRATIVE_EVIDENCE_VALUE_NOT_VISIBLE",
                "REJECTED_ACTION:CANDIDATE_NARRATIVE_EVIDENCE_NOT_DIRECT_FIT",
                "RECOMMEND_GAMES");
    }

    @Test
    void constrainsAnExplicitRecommendationQuantityAtTheSchemaAndActionBoundary() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains("Empty profile fields never block an actionable request");
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
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"evidenceIds\":[\"B60:durationMinutes\"]}]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("SELECTION_COUNT_INVALID");
                    return action(
                            "too-many-selections",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard、Loom City 和 Signal Bazaar。\",\"selections\":["
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"evidenceIds\":[\"B60:durationMinutes\"]},"
                                    + "{\"bggId\":61,\"why\":\"标注时长为 45–60 分钟。\",\"evidenceIds\":[\"B61:durationMinutes\"]},"
                                    + "{\"bggId\":63,\"why\":\"标注时长为 60–75 分钟。\",\"evidenceIds\":[\"B63:durationMinutes\"]}]}" );
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("SELECTION_COUNT_INVALID");
                    return action(
                            "exactly-two-selections",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先把 Glass Orchard 和 Loom City 放在一起看；两张卡都保留了可核对的时长边界。\",\"selections\":["
                                    + "{\"bggId\":60,\"why\":\"标注时长为 40–55 分钟。\",\"tradeoff\":\"资料没有实际桌感报告。\",\"evidenceIds\":[\"B60:durationMinutes\"]},"
                                    + "{\"bggId\":61,\"why\":\"标注时长为 45–60 分钟。\",\"tradeoff\":\"资料没有实际桌感报告。\",\"evidenceIds\":[\"B61:durationMinutes\"]}]}" );
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
    void dropsUnusedCandidateNarrativeEvidenceWithoutDiscardingTheGroundedAction() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "recommend-with-one-unused-evidence-id",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的时长边界可以直接核对。\",\"selections\":[{"
                                + "\"bggId\":60,\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"Glass Orchard 的标注时长原值是 40..55。\","
                                + "\"tradeoff\":\"现有直接事实不能证明实际桌感。\","
                                + "\"evidenceIds\":[\"B60:durationMinutes\",\"B60:complexity\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一个时长可直接核对的桌游。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text())
                        .isEqualTo("Glass Orchard 的标注时长原值是 40..55。"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DROPPED_UNUSED_CANDIDATE_NARRATIVE_EVIDENCE",
                "RECOMMEND_GAMES");
    }

    @Test
    void acceptsLocalizedRangeSeparatorsWithoutChangingTheCandidateNarrative() {
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
                                + "\"bggId\":60,\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"" + rawWhy + "\","
                                + "\"tradeoff\":\"这些目录数值不能证明实际桌感。\","
                                + "\"evidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}"),
                ignored -> action(
                        "legacy-exact-separators",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 的人数与时长边界都能直接核对。\",\"selections\":[{"
                                + "\"bggId\":60,\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"它支持 2..4 人，标注 40..55 分钟。\","
                                + "\"evidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款人数和时长都清楚的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text()).isEqualTo(rawWhy));
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsAnUnobservedNumericBoundEvenWhenAnotherCitedRangeIsVisible() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-one",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "wrong-player-bound-with-visible-duration",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看 Glass Orchard。\",\"selections\":[{"
                                + "\"bggId\":60,\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"它支持 2 到 5 人，标注时长原值是 40..55。\","
                                + "\"evidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("CANDIDATE_NARRATIVE_NUMERIC_VALUE_UNGROUNDED");
                    return action(
                            "corrected-player-bound",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"先看 Glass Orchard。\",\"selections\":[{"
                                    + "\"bggId\":60,\"narrativeMode\":\"OBSERVED_ONLY\","
                                    + "\"why\":\"它支持 2 到 4 人，标注时长原值是 40..55。\","
                                    + "\"evidenceIds\":[\"B60:playerCount\",\"B60:durationMinutes\"]}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款边界可核对的游戏。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text())
                        .isEqualTo("它支持 2 到 4 人，标注时长原值是 40..55。"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:CANDIDATE_NARRATIVE_NUMERIC_VALUE_UNGROUNDED",
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
                                + "\"evidenceIds\":[\"B61:durationMinutes\"]}]}")));

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
        assertThat(response.assistantMessage()).contains("Glass Orchard", "Loom City");
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(61);
        assertThat(response.harness().actions()).doesNotContain("REJECTED_ACTION:MESSAGE_NAMES_UNSELECTED_GAME");
    }

    @Test
    void stillRejectsARecommendationMessageThatIntroducesANewUnselectedCandidate() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-two",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "name-unselected-new-game",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先选 Glass Orchard，Loom City 下次再说。\","
                                + "\"selections\":[{\"bggId\":60,\"why\":\"它标注 40–55 分钟。\","
                                + "\"tradeoff\":\"资料没有实际桌感报告。\","
                                + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}"),
                ignored -> action(
                        "remove-unselected-new-game",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先选 Glass Orchard；卡片保留了可核对的时长边界。\","
                                + "\"selections\":[{\"bggId\":60,\"why\":\"它标注 40–55 分钟。\","
                                + "\"tradeoff\":\"资料没有实际桌感报告。\","
                                + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款短局。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).doesNotContain("Loom City");
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:MESSAGE_NAMES_UNSELECTED_GAME",
                "RECOMMEND_GAMES");
    }

    @Test
    void rejectsOnlyACrossCandidateNarrativeCitationAndKeepsTheCorrectedNaturalAnswer() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}"),
                ignored -> action(
                        "cross-candidate-evidence",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"Glass Orchard 更短。\",\"selections\":[{\"bggId\":60,"
                                + "\"why\":\"它适合先开一局。\",\"evidenceIds\":[\"B61:durationMinutes\"]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("CANDIDATE_NARRATIVE_EVIDENCE_WRONG_CANDIDATE");
                    return action(
                            "corrected-evidence",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"Glass Orchard 的标注时长更适合先开一局。\",\"selections\":[{\"bggId\":60,"
                                    + "\"why\":\"它标注 40–55 分钟，时间边界清楚。\","
                                    + "\"tradeoff\":\"这只能证明标注时长，不能保证每桌都在 55 分钟内结束。\","
                                    + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}" );
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一款短局，并说明为什么。"),
                "zh-CN");

        assertThat(response.assistantMessage()).isEqualTo("Glass Orchard 的标注时长更适合先开一局。");
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text()).isEqualTo("它标注 40–55 分钟，时间边界清楚。"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "REJECTED_ACTION:CANDIDATE_NARRATIVE_EVIDENCE_WRONG_CANDIDATE",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsGroundedCandidateCardsWhenOnlyTheRecommendationSynthesisContainsMarkup() {
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
                                + "\"evidenceIds\":[\"B60:durationMinutes\",\"B60:mechanics\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款短局并说清理由。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(rawMessage);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.reasons().getFirst().text()).isEqualTo("它标注 40–55 分钟，时长边界明确。");
            assertThat(game.tradeoffs()).containsExactly("目录资料不能证明实际桌感或等待时间。");
        });
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "PRESERVED_GROUNDED_RECOMMENDATION_WITH_MARKUP",
                "RECOMMEND_GAMES");
    }

    @Test
    void keepsGroundedCandidateCardsWhenTheSynthesisIntroducesTheCardsWithAColon() {
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
                                + "\"narrativeMode\":\"OBSERVED_ONLY\","
                                + "\"why\":\"Glass Orchard 的标注时长原值是 40..55。\","
                                + "\"tradeoff\":\"这只能证明标注时长，不能保证每桌都在 55 分钟内结束。\","
                                + "\"evidenceIds\":[\"B60:durationMinutes\"]}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款短局并说清理由。"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(rawMessage);
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.reasons().getFirst().text())
                        .isEqualTo("Glass Orchard 的标注时长原值是 40..55。"));
        assertThat(response.harness().actions()).containsExactly(
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "PRESERVED_GROUNDED_RECOMMENDATION_CONNECTIVE",
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
                    assertThat(recommendation.inputSchema())
                            .contains(
                                    "Candidate-scoped playerCount, durationMinutes, or complexity",
                                    "Taxonomy is shown elsewhere")
                            .doesNotContain("preferenceLink", "fitClaim");
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
        assertThat(response.assistantMessage()).isEqualTo("先看卡片。");
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
        assertThat(response.assistantMessage()).isEqualTo("先看卡片。");
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
    void asksOneModelWrittenQuestionWithoutAFieldOrder() {
        String publishedQuestion = "这两种理解会把候选带向不同的互动取舍。你更偏向大家一起笑，还是希望桌上能互相算计？";
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools().stream()
                                    .filter(tool -> BoardGameRecommendationAgent.ASK_TOOL.equals(tool.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .inputSchema())
                            .contains(
                                    "why this missing distinction changes the candidate set or tradeoff",
                                    "\"options\":{\"type\":\"array\"",
                                    "\"minItems\":2",
                                    "\"maxItems\":3")
                            .doesNotContain("preferenceUpdates");
                    return action(
                            "too-few-options",
                            BoardGameRecommendationAgent.ASK_TOOL,
                            "{\"question\":\"" + publishedQuestion + "\",\"options\":[\"大家一起笑\"]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("STRING_LIST_INVALID");
                    return action(
                            "ask",
                            BoardGameRecommendationAgent.ASK_TOOL,
                            "{\"question\":\"" + publishedQuestion + "\","+
                                    "\"options\":[\"大家一起笑\",\"桌上互相算计\"]}");
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
        assertThat(response.clarification().options())
                .containsExactly(
                        new BoardGameRecommendationAgent.ClarificationOption("大家一起笑", "大家一起笑"),
                        new BoardGameRecommendationAgent.ClarificationOption("桌上互相算计", "桌上互相算计"));
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:STRING_LIST_INVALID",
                "ASK_USER");
        assertThat(response.harness().catalogCalls()).isZero();
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
                            "why this missing distinction changes the candidate set or tradeoff",
                            "\"options\":{\"type\":\"array\"")
                    .doesNotContain("preferenceUpdates");
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
    void inBandClassificationKeepsACompleteGroupCountAsAVisibleReversibleAssumption() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "reply-with-contextual-count",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"message\":\"我先按你和爸妈三个人来理解，人数有变化随时改。\","
                        + "\"preferenceUpdates\":[{\"field\":\"playerCount\","
                        + "\"value\":{\"minimum\":3,\"maximum\":3},\"evidence\":\"U1\","
                        + "\"evidenceStatus\":\"CONTEXTUAL\","
                        + "\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"}]}")));

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
                        + "\"evidenceStatus\":\"CONTEXTUAL\","
                        + "\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"}]}")));

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
                        assertThat(tool.inputSchema())
                                .contains(
                                        "evidenceStatus",
                                        "evidenceReason",
                                        "DIRECT",
                                        "CONTEXTUAL",
                                        "A stated current group count N",
                                        "minimum:N,maximum:N",
                                        "Duration ceiling N",
                                        "minimum:null,maximum:N",
                                        "never {minimum:0,maximum:N}");
                    });
            assertThat(request.messages().getFirst().content())
                    .contains(
                            "A stated current group count of N players is exact",
                            "{minimum:N,maximum:N}",
                            "explicitly says at least N",
                            "within 90 minutes",
                            "{minimum:null,maximum:90}",
                            "never use zero for an open endpoint");
            return action(
                    "reply-from-context",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"我先按三个人来理解，人数有变化随时告诉我。\","
                            + "\"preferenceUpdates\":[{\"field\":\"playerCount\","
                            + "\"value\":{\"minimum\":3,\"maximum\":3},\"evidence\":\"U1\","
                            + "\"evidenceStatus\":\"CONTEXTUAL\","
                            + "\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"}]}" );
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
    void rejectsContextualClassificationOutsideTheExactPlayerCountBoundary() {
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "reply-with-exclusion",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"明白，你想换掉合作这个方向；我不会擅自把它等同成某一种固定互动模式。\","
                                + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U1\","
                                + "\"evidenceStatus\":\"CONTEXTUAL\",\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"}]}"),
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
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID",
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
                                + "\"evidenceStatus\":\"CONTEXTUAL\",\"evidenceReason\":\"COMPLETE_GROUP_INFERENCE\"},"
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\","
                                + "\"evidenceStatus\":\"DIRECT\",\"evidenceReason\":\"DIRECT\"}]}")));

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
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.messages().get(1).content())
                    .contains(
                            "focusedBggId",
                            "60",
                            "它和刚才那款相比互动怎么样",
                            "Pattern Building");
            assertThat(request.tools()).extracting(ToolSpec::name)
                    .doesNotContain(BoardGameRecommendationAgent.LOOKUP_TOOL);
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
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES", "REPLY_TO_USER");
    }

    @Test
    void letsTheAgentChooseAStructuredComparisonWhoseCellsComeOnlyFromCandidateScopedObservations() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools()).extracting(ToolSpec::name)
                    .contains(BoardGameRecommendationAgent.COMPARE_TOOL);
            assertThat(request.tools().stream()
                            .filter(tool -> BoardGameRecommendationAgent.COMPARE_TOOL.equals(tool.name()))
                            .findFirst()
                            .orElseThrow()
                            .inputSchema())
                    .as("invalid comparison axes should be impossible at schema generation time")
                    .contains("\"enum\"", "durationMinutes", "mechanics", "reportedExperience");
            assertThat(request.messages().getFirst().content())
                    .contains(
                            "Use compare_candidates only when the current user explicitly asks",
                            "request to replace, refresh, or recommend N candidates still requires recommend_games",
                            "only a possibility inferred from the label",
                            "needs actual-play reports to confirm",
                            "an earlier disclaimer never qualifies a later choice claim",
                            "taxonomy copied literally from the observation");
            var comparisonTool = request.tools().stream()
                    .filter(tool -> BoardGameRecommendationAgent.COMPARE_TOOL.equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(comparisonTool.description())
                    .contains(
                            "decisionEvidenceIds that directly justify that rule",
                            "Across the two player-visible fields",
                            "OBSERVED_ONLY",
                            "decision itself",
                            "actual-play reports",
                            "an earlier message disclaimer never qualifies it");
            assertThat(comparisonTool.inputSchema())
                    .contains(
                            "Taxonomy is not an observed effect",
                            "decisionMode",
                            "decisionEvidenceIds",
                            "Across message and decision",
                            "do not repeat a value already visible in message",
                            "an earlier disclaimer never counts");
            return action(
                    "compare-restored",
                    BoardGameRecommendationAgent.COMPARE_TOOL,
                    "{\"message\":\"Glass Orchard 的标注时长是 40..55 分钟，Loom City 是 45..60 分钟；两款的实际桌感都没有资料支持，我把这项留成待核对。\","
                            + "\"decision\":\"只按标注时长上限，Glass Orchard 的 40..55 比 Loom City 的 45..60 更低。\","
                            + "\"decisionMode\":\"OBSERVED_ONLY\","
                            + "\"decisionEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"],"
                            + "\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\",\"mechanics\",\"reportedExperience\"]}");
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
                "Glass Orchard 的标注时长是 40..55 分钟，Loom City 是 45..60 分钟；两款的实际桌感都没有资料支持，我把这项留成待核对。 "
                        + "只按标注时长上限，Glass Orchard 的 40..55 比 Loom City 的 45..60 更低。");
        assertThat(response.comparison().candidates())
                .extracting(candidate -> candidate.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.comparison().axes()).extracting(axis -> axis.subject())
                .containsExactly("durationMinutes", "mechanics", "reportedExperience");
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
                "RESTORE_KNOWN_BGG_CANDIDATES", "COMPARE_CANDIDATES");
    }

    @Test
    void acceptsDecisionEvidenceAlreadyVisibleInTheNaturalComparisonWithoutARepairTurn() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        String rawMessage = "Glass Orchard 的标注时长是 40..55 分钟，Loom City 是 45..60 分钟；其他桌感没有报告支持。";
        String rawDecision = "因此只按已显示的标注时长上限，选择 Glass Orchard。";
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "comparison-with-joint-visible-evidence",
                BoardGameRecommendationAgent.COMPARE_TOOL,
                "{\"message\":\"" + rawMessage + "\","
                        + "\"decision\":\"" + rawDecision + "\","
                        + "\"decisionMode\":\"OBSERVED_ONLY\","
                        + "\"decisionEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"],"
                        + "\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"]}")));

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
        assertThat(response.assistantMessage()).isEqualTo(rawMessage + " " + rawDecision);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES", "COMPARE_CANDIDATES");
    }

    @Test
    void keepsAValidComparisonAndLocallyDropsOnlyExcessAxes() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "comparison-with-two-extra-axes",
                BoardGameRecommendationAgent.COMPARE_TOOL,
                "{\"message\":\"先按时长、复杂度和机制对照；人数与最佳人数仍是已经核对的补充事实。\","
                        + "\"decision\":\"只按复杂度数值，Glass Orchard 的 2.4 与 Loom City 的 2.4 相同。\","
                        + "\"decisionMode\":\"OBSERVED_ONLY\","
                        + "\"decisionEvidenceIds\":[\"B60:complexity\",\"B61:complexity\"],"
                        + "\"candidateBggIds\":[60,61],"
                        + "\"subjects\":[\"durationMinutes\",\"complexity\",\"mechanics\",\"playerCount\",\"bestWith\"]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "按时长、复杂度和机制比较这两款。",
                        List.of(),
                        List.of(new DialogueMessage("user", "按时长、复杂度和机制比较这两款。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "玻璃果园", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "织机城", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.comparison().axes()).extracting(axis -> axis.subject())
                .containsExactly("durationMinutes", "complexity", "mechanics");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES",
                "DROPPED_EXCESS_COMPARISON_SUBJECTS",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsAnUnverifiedComparisonCandidateAndAllowsTheAgentToCorrectItsStructuredAction() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "compare-unverified",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"message\":\"The verified duration facts are enough for this comparison.\","
                                + "\"decision\":\"Glass Orchard lists 40..55, so choose Glass Orchard on that observed duration.\","
                                + "\"decisionMode\":\"OBSERVED_ONLY\","
                                + "\"decisionEvidenceIds\":[\"B60:durationMinutes\"],"
                                + "\"candidateBggIds\":[60,999],\"subjects\":[\"durationMinutes\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("COMPARISON_CANDIDATE_NOT_VERIFIED");
                    return action(
                            "compare-corrected",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"message\":\"The verified duration facts are enough for this comparison.\","
                                    + "\"decision\":\"Glass Orchard lists 40..55, so choose Glass Orchard on that observed duration.\","
                                    + "\"decisionMode\":\"OBSERVED_ONLY\","
                                    + "\"decisionEvidenceIds\":[\"B60:durationMinutes\"],"
                                    + "\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"]}");
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
                "RESTORE_KNOWN_BGG_CANDIDATES",
                "REJECTED_ACTION:COMPARISON_CANDIDATE_NOT_VERIFIED",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsTheAfter15ComparisonThatBuriedAnUnsupportedDecisionInsideTheAnalysis() {
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(101, game(101, "River Market", 45, List.of("Family"), List.of("Open Drafting", "Set Collection")));
        games.put(105, game(105, "Harbor Chorus", 45, List.of("Party Game"), List.of("Simultaneous Action Selection", "Voting")));
        TrackingCatalog catalog = new TrackingCatalog(games, Map.of(
                "River Market", 101,
                "Harbor Chorus", 105));
        String after15Arguments = "{\"candidateBggIds\":[101,105],"
                + "\"message\":\"River Market 时长为 30..45 分钟，复杂度 2.2，机制包含 Open Drafting 和 Set Collection。Harbor Chorus 时长同样为 30..45 分钟，复杂度 2.4，机制包含 Simultaneous Action Selection 和 Voting。两者在时长上完全一致，River Market 的数值复杂度略低。从机制标签推测，Open Drafting 可能带来更安静的个人规划节奏，而 Simultaneous Action Selection 可能意味着更高的同步互动感；但这只是可能性，需要实际游玩报告确认。鉴于 River Market 的复杂度数值更低且机制更偏向传统的策略构建，如果你偏好稍低的入门门槛和明确的收集要素，选择 River Market。\","
                + "\"subjects\":[\"durationMinutes\",\"complexity\",\"mechanics\"]}";
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "after-15-unstructured-decision",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        after15Arguments),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("REQUIRED_ARGUMENT_MISSING");
                    return action(
                            "comparison-with-observed-decision",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"message\":\"两款的标注时长都是 30..45 分钟，复杂度数值也相同；机制标签不同，但这些标签不能证明桌感。\","
                                    + "\"decision\":\"只按复杂度数值，River Market 的 2.4 与 Harbor Chorus 的 2.4 相同，因此这一项没有胜者。\","
                                    + "\"decisionMode\":\"OBSERVED_ONLY\","
                                    + "\"decisionEvidenceIds\":[\"B101:complexity\",\"B105:complexity\"],"
                                    + "\"candidateBggIds\":[101,105],"
                                    + "\"subjects\":[\"durationMinutes\",\"complexity\",\"mechanics\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "比较 River Market 和 Harbor Chorus，并直接告诉我怎么选。",
                        List.of(),
                        List.of(new DialogueMessage(
                                "user", "比较 River Market 和 Harbor Chorus，并直接告诉我怎么选。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(101, "River Market", "River Market"),
                                new BoardGameRecommendationAgent.KnownGame(105, "Harbor Chorus", "Harbor Chorus")),
                        List.of(101, 105)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage())
                .endsWith("只按复杂度数值，River Market 的 2.4 与 Harbor Chorus 的 2.4 相同，因此这一项没有胜者。");
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES",
                "REJECTED_ACTION:REQUIRED_ARGUMENT_MISSING",
                "COMPARE_CANDIDATES");
    }

    @Test
    void rejectsDecisionEvidenceOutsideTheSelectedComparisonSubjectsWithoutDiscardingTheComparisonGoal() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "decision-evidence-from-unselected-subject",
                        BoardGameRecommendationAgent.COMPARE_TOOL,
                        "{\"message\":\"两款先按时长比较。\","
                                + "\"decision\":\"Glass Orchard 支持 2..4 人，因此选择 Glass Orchard。\","
                                + "\"decisionMode\":\"OBSERVED_ONLY\","
                                + "\"decisionEvidenceIds\":[\"B60:playerCount\"],"
                                + "\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("COMPARISON_DECISION_EVIDENCE_NOT_SELECTED_SUBJECT");
                    return action(
                            "decision-evidence-corrected",
                            BoardGameRecommendationAgent.COMPARE_TOOL,
                            "{\"message\":\"Glass Orchard 标注 40..55 分钟，Loom City 标注 45..60 分钟。\","
                                    + "\"decision\":\"只按标注上限，Glass Orchard 的 40..55 比 Loom City 的 45..60 更低，因此选择 Glass Orchard。\","
                                    + "\"decisionMode\":\"OBSERVED_ONLY\","
                                    + "\"decisionEvidenceIds\":[\"B60:durationMinutes\",\"B61:durationMinutes\"],"
                                    + "\"candidateBggIds\":[60,61],\"subjects\":[\"durationMinutes\"]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "只按时长比较这两款。",
                        List.of(),
                        List.of(new DialogueMessage("user", "只按时长比较这两款。")),
                        null,
                        List.of(
                                new BoardGameRecommendationAgent.KnownGame(60, "Glass Orchard", "Glass Orchard"),
                                new BoardGameRecommendationAgent.KnownGame(61, "Loom City", "Loom City")),
                        List.of(60, 61)),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES",
                "REJECTED_ACTION:COMPARISON_DECISION_EVIDENCE_NOT_SELECTED_SUBJECT",
                "COMPARE_CANDIDATES");
    }

    @Test
    void returnsRestoredVerifiedCardsWhenAContinuationModelCallFails() {
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

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(60, 61);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().fallbackUsed()).isTrue();
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES",
                "MODEL_CALL_FAILED",
                "FALLBACK_VERIFIED_CARDS:MODEL_CALL_FAILED");
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
    void abandonsASlowContinuationRestoreWithoutSpendingTheWholeTurn() {
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
        assertThat(response.harness().actions()).containsExactly(
                "RESTORE_KNOWN_BGG_CANDIDATES_TIMED_OUT", "REPLY_TO_USER");
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
                new ConversationRequest(RecommendationProfile.empty(), "你好"),
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
                new ConversationRequest(RecommendationProfile.empty(), "你好"),
                "zh-CN",
                "alice");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(configuredOwner).hasValue("alice");
        assertThat(invokedOwner).hasValue("alice");
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
            assertThat(request.maxOutputTokens()).isEqualTo(900);
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
                                    "generate a diverse slate",
                                    "qualitative or relational recommendations",
                                    "browse the filtered catalog once instead of guessing famous titles",
                                    "Do not browse or use public discovery merely because a request is semantic",
                                    "external relationship or potentially changing fact");
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
    void keepsVerifiedCardsUsableWhenARepeatedFollowUpExhaustsTheReactBudget() {
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

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60, 61);
        assertThat(response.assistantMessage()).contains("已经核对", "不会把未核实");
        assertThat(response.harness().fallbackUsed()).isTrue();
        assertThat(response.harness().actions())
                .contains("REACT_BUDGET_EXHAUSTED", "FALLBACK_VERIFIED_CARDS:BUDGET_EXHAUSTED");
    }

    @Test
    void returnsVerifiedCardsInsteadOfAnEmptyTurnWhenTheNextModelCallFails() {
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

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(60, 61);
        assertThat(response.harness().fallbackUsed()).isTrue();
        assertThat(response.harness().actions()).contains("MODEL_CALL_FAILED", "FALLBACK_VERIFIED_CARDS:MODEL_CALL_FAILED");
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
    void doesNotPublishFreeTextWhenTheProviderSkipsTheRequiredActionProtocol() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> new Turn(
                "I will bypass the action contract and recommend from memory.",
                List.of())));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage()).doesNotContain("recommend from memory");
        assertThat(response.harness().actions()).contains("INVALID_ACTION_COUNT");
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
    void rejectsStructurallyIncompleteOrRawMarkupRepliesAndPublishesOnlyTheCorrectedPlainText() {
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "dangling-reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"message\":\"I checked the available facts, but the most important difference is:\"}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("PLAYER_MESSAGE_INCOMPLETE");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .doesNotContain(BoardGameRecommendationAgent.ASK_TOOL);
                    return action(
                            "raw-markup-reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"**Result:** the available facts do not establish that difference.\"}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("PLAYER_MESSAGE_RAW_MARKUP");
                    assertThat(request.tools()).extracting(ToolSpec::name)
                            .doesNotContain(BoardGameRecommendationAgent.ASK_TOOL);
                    return action(
                            "complete-reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"The available facts do not establish that difference, so I would keep it explicitly unknown.\"}");
                }));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "What is the deciding difference?"),
                "en");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage())
                .isEqualTo("The available facts do not establish that difference, so I would keep it explicitly unknown.")
                .doesNotContain("**", "most important difference is:");
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_ACTION:PLAYER_MESSAGE_INCOMPLETE",
                "REJECTED_ACTION:PLAYER_MESSAGE_RAW_MARKUP",
                "REPLY_TO_USER");
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

    private static Turn action(String id, String name, String arguments) {
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

    private static final class ScriptedModel implements BoardGameRecommendationModel {
        private final List<Function<Request, Turn>> turns;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedModel(List<Function<Request, Turn>> turns) {
            this.turns = List.copyOf(turns);
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Turn next(Request request) {
            int index = calls.getAndIncrement();
            if (index >= turns.size()) throw new AssertionError("unexpected model turn " + index);
            return turns.get(index).apply(request);
        }

    }

    private static final class TrackingCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games;
        private final Map<String, Integer> names;
        private int calls;
        private int maximumRequested;

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
