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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
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
    void chatsNaturallyWithoutForcingAQuestionnaireOrTouchingTheCatalog() {
        ScriptedModel model = new ScriptedModel(List.of(request -> {
            assertThat(request.tools()).extracting(tool -> tool.name())
                    .contains(
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            BoardGameRecommendationAgent.ASK_TOOL,
                            BoardGameRecommendationAgent.RECOMMEND_TOOL);
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
                    assertThat(request.messages().get(1).content())
                            .contains("想找和《马赛克花园》机制接近的游戏", "Mosaic Field");
                    return action(
                            "resolve",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"Mosaic Field\"}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("Mosaic Field", "Pattern Building", "Tile Placement");
                    return action(
                            "search",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\",\"Loom City\"]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content()).contains("60", "61");
                    return action(
                            "lookup",
                            BoardGameRecommendationAgent.LOOKUP_TOOL,
                            "{\"bggIds\":[60,61]}");
                },
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("Pattern Building", "Open Drafting");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"明白，Mosaic Field 是你补充的原名，不是换了话题。按刚核对到的图案构筑与板块放置，我会先看这两款：一款更纯粹，一款互动更明显。\","
                                    + "\"referenceBggIds\":[50],"
                                    + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]},{\"bggId\":61,\"evidenceTerms\":[\"Tile Placement\",\"Open Drafting\"]}]} ");
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
        assertThat(model.calls).hasValue(4);
    }

    @Test
    void canChooseSemanticPublicDiscoveryThenVerifyTheReturnedIds() {
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
                                new CandidateLead(60, "Glass Orchard", "Uses a shared spatial pattern", List.of(1)),
                                new CandidateLead(61, "Loom City", "Low-conflict drafting", List.of(1))),
                        List.of(new Source(1, "Independent guide", "https://example.test/guide", "example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"games with shared spatial pattern building and low conflict\",\"types\":[\"ABSTRACT\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("Independent guide", "60", "61");
                    return action(
                            "lookup",
                            BoardGameRecommendationAgent.LOOKUP_TOOL,
                            "{\"bggIds\":[60,61]}");
                },
                ignored -> action(
                        "recommend",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我先按你描述的空间拼搭感找了公开候选，再逐一核对了 BGG；这两款方向不同，你可以从互动强弱来选。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]},{\"bggId\":61,\"evidenceTerms\":[\"Open Drafting\"]}]}")));

        var response = agent(model, catalog, research).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想要有空间拼搭感、冲突别太强的桌游"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.researchSources()).singleElement().satisfies(source ->
                assertThat(source.domain()).isEqualTo("example.test"));
        assertThat(response.games().getFirst().reasons())
                .anyMatch(reason -> reason.kind() == BoardGameRecommendationAgent.ReasonKind.WEB_RESEARCH
                        && reason.sourceIndexes().equals(List.of(1)));
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
    }

    @Test
    void returnsAnInvalidFinalIdToTheAgentAndLetsItRecover() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "bad-final",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先试这款。\",\"referenceBggIds\":[],\"selections\":[{\"bggId\":999,\"evidenceTerms\":[]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("FINAL_ID_NOT_VERIFIED");
                    return action(
                            "search",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Glass Orchard\"]}");
                },
                ignored -> action(
                        "lookup",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60]}"),
                ignored -> action(
                        "valid-final",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"刚才的候选没有经过核对；这次我只保留已经确认过资料的这一款。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]}]}")));

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
                        "remember",
                        BoardGameRecommendationAgent.UPDATE_TOOL,
                        "{\"players\":{\"value\":4,\"evidence\":\"4 个人，最多 60 分钟\"},"
                                + "\"maxMinutes\":{\"value\":60,\"evidence\":\"4 个人，最多 60 分钟\"}}"),
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Long Mosaic\",\"Glass Orchard\"]}"),
                ignored -> action(
                        "lookup",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[62,60]}"),
                ignored -> action(
                        "bad-gate",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"先看长局。\",\"referenceBggIds\":[],\"selections\":[{\"bggId\":62,\"evidenceTerms\":[\"Pattern Building\"]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("FINAL_ID_FAILS_HARD_GATES");
                    return action(
                            "good-gate",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"按你说的 4 人和一小时上限，长局不合适；这款才在范围内。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]}]} ");
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
    void rejectsProseThatBypassesFactualCardsAndLetsTheAgentRewriteIt() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\",\"Long Mosaic\"]}"),
                ignored -> action(
                        "lookup",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60,62]}"),
                ignored -> action(
                        "bad-copy",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"message\":\"我推荐 Glass Orchard；Long Mosaic 也很适合，而且节奏更轻松。\","
                                + "\"referenceBggIds\":[],"
                                + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]}]}"),
                request -> {
                    assertThat(request.messages().getLast().content())
                            .contains("MESSAGE_NAMES_CARD_GAME");
                    return action(
                            "rewritten-copy",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我只保留已经入选且证据足够的这一款；卡片里是核对过的共同机制。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "推荐一款图案构筑游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).doesNotContain("Long Mosaic");
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:MESSAGE_NAMES_CARD_GAME", "RECOMMEND_GAMES");
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
            assertThat(request.maxOutputTokens()).isEqualTo(1_200);
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
                        "search-1",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Unknown Design\"]}"),
                ignored -> action(
                        "search-2",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Unknown Design\"]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("REPEATED_ACTION");
                    return action(
                            "reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"message\":\"这个线索暂时没查到，我先不乱猜。你愿意换一种描述吗？\",\"referencedBggIds\":[]}");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "找 Unknown Design"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).contains("REJECTED_REPEATED_ACTION");
    }

    @Test
    void leavesRoomForATerminalDecisionAfterSixUsefulObservations() {
        TrackingCatalog catalog = catalog();
        ScriptedModel model = new ScriptedModel(List.of(
                ignored -> action(
                        "resolve",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Localized Mosaic\"}"),
                ignored -> action(
                        "search",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Glass Orchard\"]}"),
                ignored -> action(
                        "lookup-reference",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[60]}"),
                ignored -> action(
                        "discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"query\":\"pattern building games similar to the verified reference\",\"types\":[\"ABSTRACT\"]}"),
                ignored -> action(
                        "browse",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"types\":[\"ABSTRACT\"],\"limit\":4}"),
                ignored -> action(
                        "lookup-candidate",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[61]}"),
                request -> {
                    assertThat(request.messages().getLast().content()).contains("Loom City", "Tile Placement");
                    return action(
                            "recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"message\":\"我先核对了参考方向，再比较完整资料；这两款都保留图案或板块构筑，但侧重点不同。\","
                                    + "\"referenceBggIds\":[],"
                                    + "\"selections\":[{\"bggId\":60,\"evidenceTerms\":[\"Pattern Building\"]},{\"bggId\":61,\"evidenceTerms\":[\"Tile Placement\"]}]} ");
                }));

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "想找和本地译名那款相似的游戏"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(7);
        assertThat(response.harness().actions()).containsExactly(
                "RESOLVE_BGG_REFERENCE",
                "SEARCH_BGG_BY_NAME",
                "LOOKUP_BGG_CANDIDATES",
                "DISCOVER_CANDIDATES",
                "SEARCH_BGG_CATALOG",
                "LOOKUP_BGG_CANDIDATES",
                "RECOMMEND_GAMES");
    }

    @Test
    void stopsAfterTheBoundedNumberOfObservationDependentModelTurns() {
        TrackingCatalog catalog = new TrackingCatalog();
        List<Function<Request, Turn>> turns = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Function<Request, Turn>) request -> {
                    if (index == 0) {
                        assertThat(request.messages().get(1).content())
                                .contains("\"semanticPublicDiscovery\":false", "\"maximumActionCalls\":8");
                    }
                    if (index == 7) {
                        assertThat(request.messages().getLast().content())
                                .contains("\"remainingModelCalls\":1", "\"remainingActionCalls\":1");
                    }
                    return action(
                            "search-" + index,
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Unknown Design " + index + "\"]}");
                })
                .toList();
        ScriptedModel model = new ScriptedModel(turns);

        var response = agent(model, catalog, noResearch()).converse(
                new ConversationRequest(RecommendationProfile.empty(), "找一款很冷门的设计"),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.harness().modelCalls()).isEqualTo(8);
        assertThat(response.harness().catalogCalls()).isEqualTo(8);
        assertThat(response.harness().actions()).contains("REACT_BUDGET_EXHAUSTED");
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
        var properties = new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.66"));
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
