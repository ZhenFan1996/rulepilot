package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
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
                new ConversationRequest(RecommendationProfile.empty(), "我想玩 Mosaic Field"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .containsExactly(50);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
        assertThat(catalog.calls).isEqualTo(1);
    }

    @Test
    void chatsNaturallyWithoutForcingAQuestionnaireOrTouchingTheCatalog() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools()).extracting(tool -> tool.name())
                    .contains(
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            BoardGameRecommendationAgent.ASK_TOOL)
                    .doesNotContain(BoardGameRecommendationAgent.RECOMMEND_TOOL);
            assertThat(request.messages().get(1).content()).contains("最近总是玩重策，今天只想聊聊桌游设计");
            return action(
                    "reply",
                    BoardGameRecommendationAgent.REPLY_TOOL,
                    "{\"message\":\"当然可以。最近哪种设计最让你念念不忘？我们可以先随便聊，不急着挑游戏。\",\"referencedBggIds\":[]}");
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
    void treatsAStandaloneOriginalTitleAsContinuationAndUsesObservedMechanics() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "Games like X",
                                    "per-turn comparison goal",
                                    "later updates are ignored");
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
                            "{\"title\":\"Mosaic Field\",\"purpose\":\"COMPARISON_REFERENCE\","
                                    + "\"preferenceUpdates\":[{\"field\":\"type\",\"value\":\"STRATEGY\",\"evidence\":\"U1\"}]}");
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
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                    + "\"preferenceUpdates\":[{\"field\":\"type\",\"value\":\"ABSTRACT\",\"evidence\":\"Mosaic Field\"}]}");
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
                "REJECTED_PREFERENCE_UPDATE:GAME_TYPE_EVIDENCE_MISMATCH",
                "RESOLVE_BGG_REFERENCE",
                "IGNORED_POST_REFERENCE_PREFERENCE_UPDATE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
        assertThat(response.harness().catalogCalls()).isEqualTo(3);
        assertThat(model.calls).hasValue(3);
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
                        "{\"title\":\"Mosaic Field\",\"purpose\":\"DISCUSSION_SUBJECT\",\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"2 人\"}]}"),
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
                            .contains(BoardGameRecommendationAgent.SEARCH_TOOL)
                            .doesNotContain(BoardGameRecommendationAgent.DISCOVER_TOOL);
                    assertThat(request.messages().get(1).content())
                            .contains(
                                    "\"semanticPublicDiscovery\":false",
                                    "\"semanticPublicDiscoveryFallbackAfterTitleInspection\":true");
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
                            .containsExactly(BoardGameRecommendationAgent.RECOMMEND_TOOL);
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
                    assertThat(request.tools().getFirst().inputSchema())
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
                            .containsExactly(BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(request.tools().getFirst().inputSchema())
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
                            .contains(BoardGameRecommendationAgent.SEARCH_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.DISCOVER_TOOL,
                                    BoardGameRecommendationAgent.RESEARCH_TOOL);
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
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"2 人\"}]}");
                },
                request -> {
                    requestCharacters.add(requestCharacters(request));
                    assertThat(request.messages()).hasSize(4);
                    assertThat(request.messages().stream()
                                    .filter(message -> message.role() == BoardGameRecommendationModel.Role.TOOL))
                            .hasSize(1);
                    assertThat(request.messages().getLast().content())
                            .contains("\"players\":2", "Glass Orchard", "Pattern Building");
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
    void keepsAUsefulCompositeReadWhenItsAttachedPreferenceEvidenceNeedsCorrection() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search-with-paraphrased-evidence",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":4,\"evidence\":\"四位玩家\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("PREFERENCE_EVIDENCE_NOT_GROUNDED", "Glass Orchard", "Pattern Building");
                    return action(
                            "recommend-with-grounded-evidence",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按四人条件，我先给你一个经过核对的方向，详情见卡片。\","
                                    + "\"selections\":[{\"bggId\":60}],"
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":4,\"evidence\":\"四个人\"}]} ");
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
                "RECOMMEND_GAMES");
    }

    @Test
    void citesStableUserMessageIdsForHardPreferencesWithoutAFormattingRetry() {
        TrackingCatalog catalog = catalogWithThreeShortGames();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.tools())
                            .extracting(tool -> tool.name())
                            .contains(BoardGameRecommendationAgent.SEARCH_TOOL)
                            .doesNotContain(
                                    BoardGameRecommendationAgent.BROWSE_TOOL,
                                    BoardGameRecommendationAgent.DISCOVER_TOOL);
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
                                    + "{\"field\":\"maxMinutes\",\"value\":90,\"evidence\":\"U2\"},"
                                    + "{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U2\"}]} ");
                },
                request -> {
                    assertThat(request.tools())
                            .extracting(tool -> tool.name())
                            .containsExactly(BoardGameRecommendationAgent.RECOMMEND_TOOL);
                    assertThat(requestCharacters(request)).isLessThan(14_000);
                    assertThat(request.messages().getLast().content())
                            .contains(
                                    "\"players\":4",
                                    "\"maxMinutes\":90",
                                    "\"interaction\":\"ANY\"",
                                    "INTERACTION_EVIDENCE_MISMATCH");
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
                "REJECTED_PREFERENCE_UPDATE:INTERACTION_EVIDENCE_MISMATCH",
                "UPDATE_PREFERENCES",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void doesNotTurnQualitativeHeavinessIntoANumericComplexityCeiling() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search-with-invented-ceiling",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"maxWeight\",\"value\":5,\"evidence\":\"重策\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("WEIGHT_EVIDENCE_MISMATCH", "Glass Orchard");
                    return action(
                            "recommend-without-false-ceiling",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我按你想要的重策方向挑了一个经过核对的候选，详情见卡片。\","
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想玩重策"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().maxWeight()).isNull();
        assertThat(response.harness().actions())
                .contains("REJECTED_PREFERENCE_UPDATE:WEIGHT_EVIDENCE_MISMATCH", "RECOMMEND_GAMES");
    }

    @Test
    void acceptsAnExplicitPositiveInteractionMode() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "ask-with-explicit-mode",
                BoardGameRecommendationAgent.ASK_TOOL,
                "{\"question\":\"竞争模式记下了。你更偏短局还是长局？\","
                        + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U1\"}]}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "这次明确想玩竞争对抗模式"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.COMPETITIVE);
        assertThat(response.harness().actions()).containsExactly("UPDATE_PREFERENCES", "ASK_USER");
    }

    @Test
    void rejectsANegatedInteractionModeWithoutDiscardingTheUsefulCatalogRead() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "inspect-with-negated-mode",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"],"
                                + "\"preferenceUpdates\":[{\"field\":\"interaction\",\"value\":\"COOPERATIVE\",\"evidence\":\"U1\"}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("INTERACTION_EVIDENCE_MISMATCH", "Glass Orchard");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"合作口味不会被误记成硬条件，我先给你一个经过核对的方向。\","
                                    + "\"selections\":[{\"bggId\":60}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "合作游戏已经玩腻了，想换换口味"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.harness().actions()).containsExactly(
                "REJECTED_PREFERENCE_UPDATE:INTERACTION_EVIDENCE_MISMATCH",
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
                            "mechanics and exclusions stay semantic");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你补充的双人条件，我保留两种图案构筑方向，具体差异见卡片。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60},{\"bggId\":61}],"
                                    + "\"preferenceUpdates\":[{\"field\":\"players\",\"value\":2,\"evidence\":\"两个人也好玩\"}]}");
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
                                + "{\"field\":\"players\",\"value\":4,\"evidence\":\"4 个人\"},"
                                + "{\"field\":\"maxMinutes\",\"value\":60,\"evidence\":\"最多 60 分钟\"}]}"),
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
    void sanitizesProseThatDuplicatesFactualCardsWithoutAnotherModelTurn() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Long Mosaic\"]}"),
                ignored -> action(
                        "bad-copy",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我推荐 Glass Orchard；Long Mosaic 也很适合，而且节奏更轻松。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60}]}")));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage())
                .contains("具体差异都在卡片里")
                .doesNotContain("Glass Orchard", "Long Mosaic");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsExactly(
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "SANITIZED_CARD_MESSAGE",
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
    void asksOneModelWrittenQuestionWithoutAFieldOrder() {
        ScriptedModel model = new ScriptedModel(List.of(ignored -> action(
                "ask",
                BoardGameRecommendationAgent.ASK_TOOL,
                "{\"question\":\"你说的‘热闹’，更偏向大家一起笑，还是希望桌上能互相算计？\"}")));

        var response = agent(model, new TrackingCatalog(), noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找一款热闹的"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.clarification().field())
                .isEqualTo(BoardGameRecommendationAgent.PreferenceField.CONVERSATION);
        assertThat(response.assistantMessage()).contains("一起笑", "互相算计");
        assertThat(response.harness().catalogCalls()).isZero();
    }

    @Test
    void canContinueAFocusedGameConversationAndGroundAProseReplyInItsVerifiedId() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains("focusedBggId", "60", "它和刚才那款相比互动怎么样");
                    return action(
                            "lookup-focused",
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
        assertThat(response.harness().actions()).containsExactly("LOOKUP_BGG_CANDIDATES", "REPLY_TO_USER");
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
        assertThat(response.assistantMessage()).contains("暂时没能完成");
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).contains("UNAVAILABLE:MODEL_NOT_CONFIGURED");
        assertThat(catalog.calls).isZero();
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
                            .doesNotContain(BoardGameRecommendationAgent.SEARCH_TOOL);
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
                                    BoardGameRecommendationAgent.BROWSE_TOOL);
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
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                request -> {
                    assertThat(request.messages().get(1).content())
                            .contains("四个人", "科幻主题", "德式重策");
                    assertThat(request.messages().getFirst().content())
                            .contains(
                                    "generate a diverse slate",
                                    "prefer inspect_candidate_titles",
                                    "Do not browse merely because a request is semantic");
                    return action(
                            "inspect",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"],"
                                    + "\"preferenceUpdates\":["
                                    + "{\"field\":\"players\",\"value\":4,\"evidence\":\"四个人\"},"
                                    + "{\"field\":\"type\",\"value\":\"STRATEGY\",\"evidence\":\"德式重策\"}]} ");
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

    private BoardGameRecommendationAgent agent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationCatalog catalog,
            BoardGameRecommendationWebResearch research) {
        return agent(model, catalog, research, Duration.ofSeconds(55));
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
        Map<Integer, Game> games = new LinkedHashMap<>();
        games.put(50, game(50, "Mosaic Field", 45, List.of("Abstract Strategy"), List.of("Pattern Building", "Tile Placement")));
        games.put(60, game(60, "Glass Orchard", 55, List.of("Abstract Strategy"), List.of("Pattern Building")));
        games.put(61, game(61, "Loom City", 60, List.of("Abstract Strategy"), List.of("Tile Placement", "Open Drafting")));
        games.put(62, game(62, "Long Mosaic", 150, List.of("Abstract Strategy"), List.of("Pattern Building")));
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

    private static Game game(
            int id,
            String name,
            int maximumMinutes,
            List<String> categories,
            List<String> mechanics) {
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
