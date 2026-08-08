package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Choice;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Confidence;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueAct;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Plan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.PreferenceHypothesis;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.PreferencePatch;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.ResearchedReason;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Slate;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalogRepository;
import com.rulepilot.catalog.application.BggRankedCatalogService;
import com.rulepilot.catalog.application.BoardGameGeekCatalog;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationAgentTest {

    @Test
    void recommendsAfterOneUsefulGuidedPreferenceAndFallsBackWithoutAProvider() {
        Fixture fixture = new Fixture(request -> Optional.empty(), request -> Optional.empty(), new NoResearch());

        var first = fixture.agent.converse(new ConversationRequest(RecommendationProfile.empty(), ""), "zh-CN");
        var second = fixture.agent.converse(new ConversationRequest(
                new RecommendationProfile(4, null, null, BggGameType.ALL, InteractionPreference.ANY), ""), "zh-CN");

        assertThat(first.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(first.clarification().field()).isEqualTo(PreferenceField.PLAYERS);
        assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(second.clarification().field()).isEqualTo(PreferenceField.DURATION);
        assertThat(second.mode()).isEqualTo(DecisionMode.DETERMINISTIC);
        assertThat(second.harness().modelCalls()).isZero();
        assertThat(second.harness().catalogCalls()).isEqualTo(1);
        assertThat(second.harness().fallbackUsed()).isTrue();
    }

    @Test
    void appliesOnlyExplicitHardConstraintsAcrossTheFullSnapshotCandidateQuery() {
        Fixture fixture = new Fixture(request -> Optional.empty(), request -> Optional.empty(), new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(), "我们 4 人，90 分钟，中度复杂度，想玩合作策略游戏"), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.sourceCount()).isEqualTo(179_737);
        assertThat(response.profile().type()).isEqualTo(BggGameType.STRATEGY);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.COOPERATIVE);
        assertThat(fixture.repository.queries).singleElement().satisfies(query -> {
            assertThat(query.type()).isEqualTo(BggGameType.STRATEGY);
            assertThat(query.size()).isEqualTo(8);
        });
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId())
                .contains(10, 20)
                .doesNotContain(30, 40);
        assertThat(response.games().getFirst().matches()).contains("BGG 标注了合作游戏机制");
    }

    @Test
    void usesThePlannerUserModelAndCandidateBoundComposerInsteadOfMechanicalClassification() {
        UserModel model = new UserModel(
                "新手家庭局，可能在意讲解负担和共同参与",
                List.of(new PreferenceHypothesis("可能偏好低教学摩擦", Confidence.MEDIUM, "不想讲半天规则")));
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, null, BggGameType.FAMILY, null),
                model,
                "我先给你几个方向。",
                "哪种桌上气氛更接近你们？",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "我把容易进入状态放在第一位。",
                        "玩完后告诉我哪一点不合适。",
                        List.of(new Choice(
                                20,
                                List.of("可能更接近你说的轻松进入状态"),
                                List.of(),
                                List.of("仍需要确认你们是否喜欢轮抽"))))),
                new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "第一次带家人玩，不想讲半天规则",
                List.of(),
                List.of(new DialogueMessage("user", "第一次带家人玩，不想讲半天规则")),
                null), "zh-CN");

        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_ASSISTED);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.userModel().summary()).contains("讲解负担");
        assertThat(response.userModel().hypotheses()).singleElement()
                .extracting(BoardGameRecommendationAgent.PreferenceHypothesisView::confidence)
                .isEqualTo("MEDIUM");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(20);
            assertThat(game.reasons()).anySatisfy(reason -> {
                assertThat(reason.kind()).isEqualTo(ReasonKind.PREFERENCE_INFERENCE);
                assertThat(reason.text()).contains("轻松进入状态");
            });
        });
        assertThat(response.harness().actions())
                .containsExactly("PLAN_DIALOGUE", "SEARCH_BGG_CATALOG", "COMPOSE_RECOMMENDATIONS");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
    }

    @Test
    void retrievesAndEnforcesExplicitMetadataFeaturesBeforeModelReranking() {
        AtomicInteger discoveryCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch discovery = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                assertThat(request.signals()).singleElement().satisfies(signal -> {
                    assertThat(signal.term()).isEqualTo("Science Fiction");
                    assertThat(signal.source()).isEqualTo(FeatureSource.BGG_METADATA);
                });
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(60, "Game 60", List.of(1))),
                        List.of(new Source(1, "BGG item", "https://boardgamegeek.com/boardgame/60", "boardgamegeek.com"))));
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("experience research was not requested");
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("明确想要科幻题材", List.of()),
                "我会先按题材找候选。",
                "你更喜欢太空探索还是叙事冒险？",
                true,
                "查更多资料",
                new RetrievalPlan(
                        List.of(BggGameType.THEMATIC, BggGameType.STRATEGY),
                        List.of(new FeatureConstraint(
                                "Science Fiction", FeatureMode.REQUIRED, FeatureSource.BGG_METADATA, "科幻主题")),
                        true));
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    throw new AssertionError("an explicit verified metadata constraint should not pay for composition");
                },
                discovery);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "我想玩科幻主题的桌游",
                List.of(),
                List.of(new DialogueMessage("user", "我想玩科幻主题的桌游")),
                null), "zh-CN");

        assertThat(fixture.repository.queries).isEmpty();
        assertThat(discoveryCalls).hasValue(1);
        assertThat(response.harness().actions())
                .contains("DISCOVER_CANDIDATES", "LOOKUP_BGG_CANDIDATES", "RANK_STRUCTURED_CANDIDATES")
                .doesNotContain("COMPOSE_RECOMMENDATIONS");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(60);
            assertThat(game.game().details().categories()).contains("Science Fiction");
            assertThat(game.matches()).contains("BGG 元数据命中你提到的“科幻主题”");
        });
    }

    @Test
    void expandsRecallWhenARequiredFeatureIsAbsentFromTheInitialRankPool() {
        BoardGameRecommendationWebResearch discovery = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(70, "Game 70", List.of(1))),
                        List.of(new Source(
                                1,
                                "BGG item",
                                "https://boardgamegeek.com/boardgame/70",
                                "boardgamegeek.com"))));
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("metadata verification must not invoke experience research");
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(4, 120, null, null, null),
                new UserModel("四人拍卖游戏", List.of()),
                "我会按明确机制找候选。",
                "还想调整什么？",
                false,
                "",
                new RetrievalPlan(
                        List.of(BggGameType.STRATEGY),
                        List.of(new FeatureConstraint(
                                "Auction",
                                FeatureMode.REQUIRED,
                                FeatureSource.BGG_METADATA,
                                "拍卖机制")),
                        false));
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    throw new AssertionError("verified required metadata must use structured ranking");
                },
                discovery);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "推荐四人两小时内的拍卖游戏",
                List.of(),
                List.of(new DialogueMessage("user", "推荐四人两小时内的拍卖游戏")),
                null), "zh-CN");

        assertThat(response.harness().actions())
                .contains("DISCOVER_CANDIDATES", "LOOKUP_BGG_CANDIDATES", "RANK_STRUCTURED_CANDIDATES");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(70);
            assertThat(game.game().details().mechanics()).contains("Auction");
        });
    }

    @Test
    void doesNotRepeatWebDiscoveryAfterNativeToolsReturnAReadyCandidatePool() {
        AtomicInteger turns = new AtomicInteger();
        BoardGameRecommendationCandidateModel candidateModel = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                if (turns.getAndIncrement() == 0) {
                    return new Turn("", List.of(new ToolCall(
                            "search-1",
                            BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                            "{\"names\":[\"Game 10\",\"Game 11\"]}")));
                }
                return new Turn("", List.of(new ToolCall(
                        "lookup-1",
                        BoardGameRecommendationCandidateAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[10,11]}")));
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                throw new AssertionError("ready native candidates must not trigger duplicate web discovery");
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("the planner did not request experience research");
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(4, 90, null, null, InteractionPreference.COOPERATIVE),
                new UserModel("四人合作局，希望容易进入状态", List.of()),
                "我会先核对 BGG 候选。",
                "还想调整什么？",
                false,
                "",
                new RetrievalPlan(
                        List.of(BggGameType.STRATEGY),
                        List.of(new FeatureConstraint(
                                "easy to teach",
                                FeatureMode.PREFERRED,
                                FeatureSource.EXPERIENCE,
                                "容易进入状态")),
                        true));
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "先从合作候选里选。",
                        "",
                        List.of(new Choice(10, List.of("BGG 硬条件匹配"), List.of(), List.of())))),
                research,
                candidateModel);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "4 人，90 分钟，想玩容易上手的合作游戏",
                List.of(),
                List.of(new DialogueMessage("user", "4 人，90 分钟，想玩容易上手的合作游戏")),
                null), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions())
                .contains(
                        "MODEL_SELECT_TOOLS",
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "COMPOSE_RECOMMENDATIONS")
                .doesNotContain("DISCOVER_CANDIDATES");
    }

    @Test
    void asksAUsageQuestionWithoutSearchingWhenThePlannerSaysARecommendationWouldBeArbitrary() {
        Plan plan = new Plan(
                DialogueAct.ASK,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("还没有足够场景", List.of()),
                "可以，先说说这次是什么场合？",
                "是熟人聚会、情侣两人，还是亲子时间？",
                false,
                "");
        Fixture fixture = new Fixture(request -> Optional.of(plan), request -> Optional.empty(), new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "帮我推荐桌游",
                List.of(),
                List.of(new DialogueMessage("user", "帮我推荐桌游")),
                null), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.clarification().field()).isEqualTo(PreferenceField.CONVERSATION);
        assertThat(response.clarification().prompt()).contains("熟人聚会");
        assertThat(fixture.repository.queries).isEmpty();
        assertThat(response.harness().catalogCalls()).isZero();
    }

    @Test
    void keepsAPlannerQuestionConversationalAfterPreferencesAlreadyExist() {
        Plan plan = new Plan(
                DialogueAct.ASK,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("已经知道是四人局，仍需确认想要的体验", List.of()),
                "四人没问题。你们今晚更想合作解题，还是互相较量？",
                "更想合作解题，还是互相较量？",
                false,
                "");
        Fixture fixture = new Fixture(request -> Optional.of(plan), request -> Optional.empty(), new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                new RecommendationProfile(4, null, null, BggGameType.ALL, InteractionPreference.ANY),
                "人数就是四个，先聊聊方向",
                List.of(),
                List.of(new DialogueMessage("user", "人数就是四个，先聊聊方向")),
                null), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.assistantMessage()).contains("更想合作解题");
        assertThat(fixture.repository.queries).isEmpty();
        assertThat(response.harness().catalogCalls()).isZero();
    }

    @Test
    void keepsOrdinaryDialogueConversationalWithoutForcingCatalogWork() {
        Plan plan = new Plan(
                DialogueAct.RESPOND,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("用户在回应上一轮说明", List.of()),
                "对，就是这个意思。你可以继续问它的机制、回合流程，或者让我拿它和别的游戏比较。",
                "",
                false,
                "");
        Fixture fixture = new Fixture(request -> Optional.of(plan), request -> Optional.empty(), new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                new RecommendationProfile(4, 90, null, BggGameType.STRATEGY, InteractionPreference.ANY),
                "明白了",
                List.of(),
                List.of(new DialogueMessage("user", "明白了")),
                20), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).contains("继续问它的机制");
        assertThat(fixture.repository.queries).isEmpty();
        assertThat(fixture.repository.focusedIds).isEmpty();
        assertThat(response.harness().catalogCalls()).isZero();
    }

    @Test
    void researchesAFocusedGameAndKeepsEveryClaimAttachedToAnAllowListedSource() {
        AtomicInteger researchCalls = new AtomicInteger();
        var research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                researchCalls.incrementAndGet();
                assertThat(request.candidates()).extracting(BoardGameRecommendationAdvisor.Candidate::bggId)
                        .containsExactly(20);
                assertThat(request.question()).isEqualTo("查证这款游戏的桌上节奏");
                return Optional.of(new Research(
                        List.of(new GameResearch(20, List.of(new Observation("玩家普遍认为轮次流畅", List.of(1))))),
                        List.of(new Source(1, "Publisher guide", "https://publisher.example/game-20", "publisher.example"))));
            }
        };
        Plan plan = new Plan(
                DialogueAct.EXPLAIN,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("想判断是否适合家庭局", List.of()),
                "我查一下实际体验。",
                "",
                true,
                "查证这款游戏的桌上节奏");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "这款更适合想轻松互动的家庭局。",
                        "",
                        List.of(new Choice(
                                20,
                                List.of("可能符合你希望全桌参与的倾向"),
                                List.of(new ResearchedReason("多份体验资料提到轮次较流畅", List.of(1))),
                                List.of())))),
                research);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "介绍一下 Game 20",
                List.of(),
                List.of(new DialogueMessage("user", "介绍一下 Game 20")),
                20), "zh-CN");

        assertThat(researchCalls).hasValue(1);
        assertThat(fixture.repository.focusedIds).containsExactly(20);
        assertThat(response.games()).singleElement().satisfies(game -> assertThat(game.reasons())
                .anySatisfy(reason -> {
                    assertThat(reason.kind()).isEqualTo(ReasonKind.WEB_RESEARCH);
                    assertThat(reason.text()).isEqualTo("玩家普遍认为轮次流畅");
                    assertThat(reason.sourceIndexes()).containsExactly(1);
                }));
        assertThat(response.researchSources()).singleElement().satisfies(source -> {
            assertThat(source.index()).isEqualTo(1);
            assertThat(source.url()).startsWith("https://");
        });
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).contains("RESEARCH_GAME_QUESTION");
    }

    @Test
    void looksUpAFocusedGameByIdWithoutBroadSearchOrUnrequestedResearch() {
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("focused BGG facts do not require unrequested web research");
            }
        };
        Plan plan = new Plan(
                DialogueAct.EXPLAIN,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("正在了解刚才展示的候选", List.of()),
                "我直接介绍刚才那款。",
                "",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    assertThat(request.act()).isEqualTo(DialogueAct.EXPLAIN);
                    assertThat(request.candidates()).singleElement().satisfies(candidate -> {
                        assertThat(candidate.description()).contains("回合中派遣代理人");
                        assertThat(candidate.mechanics()).contains("Card Drafting");
                    });
                    return Optional.of(new Slate(
                            "这是刚才那款游戏的详细介绍。",
                            "",
                            List.of(new Choice(20, List.of("延续刚才的候选"), List.of(), List.of()))));
                },
                research);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "介绍一下刚才那款",
                List.of(),
                List.of(new DialogueMessage("user", "介绍一下刚才那款")),
                20), "zh-CN");

        assertThat(fixture.repository.queries).isEmpty();
        assertThat(fixture.repository.focusedIds).containsExactly(20);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions())
                .containsExactly("PLAN_DIALOGUE", "LOOKUP_BGG_GAME", "COMPOSE_GAME_RESPONSE");
        assertThat(response.clarification()).isNull();
        assertThat(response.games()).singleElement()
                .extracting(game -> game.game().ranking().bggId())
                .isEqualTo(20);
    }

    @Test
    void treatsAFocusedIdAsConversationContextWhenThePlannerRequestsAlternatives() {
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, new BigDecimal("2.3"), null, null),
                new UserModel("想以当前游戏为参照找更轻的替代", List.of()),
                "我用它作参照，换几款更轻的。",
                "哪一款更接近？",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    assertThat(request.referenceGame()).isNotNull();
                    assertThat(request.referenceGame().bggId()).isEqualTo(20);
                    assertThat(request.referenceGame().mechanics()).contains("Card Drafting", "Worker Placement");
                    assertThat(request.candidates()).extracting(BoardGameRecommendationAdvisor.Candidate::bggId)
                            .doesNotContain(20);
                    return Optional.of(new Slate(
                            "这几款保留互动感，但更容易上手。",
                            "哪一款更接近？",
                            List.of(new Choice(30, List.of("相对更轻"), List.of(), List.of()))));
                },
                new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "有没有类似但更简单的？",
                List.of(),
                List.of(new DialogueMessage("user", "有没有类似但更简单的？")),
                20), "zh-CN");

        assertThat(fixture.repository.queries).isNotEmpty();
        assertThat(fixture.repository.focusedIds).containsExactly(20);
        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).containsExactly(30);
        assertThat(response.harness().actions())
                .contains("LOOKUP_REFERENCE_GAME", "SEARCH_BGG_CATALOG")
                .doesNotContain("LOOKUP_BGG_GAME");
    }

    @Test
    void letsThePlannerResearchARegularRecommendationWhenExperienceEvidenceWouldChangeTheChoice() {
        AtomicInteger researchCalls = new AtomicInteger();
        var research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                researchCalls.incrementAndGet();
                assertThat(request.candidates()).extracting(BoardGameRecommendationAdvisor.Candidate::bggId)
                        .contains(10, 20);
                return Optional.of(new Research(
                        List.of(new GameResearch(20, List.of(new Observation("体验报告认为教学摩擦较低", List.of(1))))),
                        List.of(new Source(1, "Independent review", "https://review.example/game-20", "review.example"))));
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("新手聚会，担心讲解拖慢开局", List.of()),
                "我会把真实教学体验也纳入选择。",
                "",
                true,
                "查证候选的教学摩擦和第一次开局体验",
                new RetrievalPlan(
                        List.of(),
                        List.of(new FeatureConstraint(
                                "low teach friction",
                                FeatureMode.PREFERRED,
                                FeatureSource.EXPERIENCE,
                                "担心讲太久")),
                        false));
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    assertThat(request.focusedBggId()).isNull();
                    assertThat(request.research().games()).isNotEmpty();
                    return Optional.of(new Slate(
                            "结合目录和实际体验，先试这一款。",
                            "玩过后告诉我讲解是否仍然偏长。",
                            List.of(new Choice(
                                    20,
                                    List.of("可能更符合你希望快速开局的倾向"),
                                    List.of(new ResearchedReason("体验资料提到教学摩擦较低", List.of(1))),
                                    List.of()))));
                },
                research);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "第一次带朋友玩，我更担心讲太久而不是规则复杂本身",
                List.of(),
                List.of(new DialogueMessage("user", "第一次带朋友玩，我更担心讲太久而不是规则复杂本身")),
                null), "zh-CN");

        assertThat(researchCalls).hasValue(1);
        assertThat(response.harness().actions()).contains("RESEARCH_GAME_FIT", "COMPOSE_RECOMMENDATIONS");
        assertThat(response.games()).singleElement().satisfies(game -> assertThat(game.reasons())
                .anyMatch(reason -> reason.kind() == ReasonKind.WEB_RESEARCH));
    }

    @Test
    void excludesPreviouslyShownGamesAndNeverExceedsPerTurnHarnessBudgets() {
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("想换轻松一点", List.of()),
                "换一个方向。",
                "",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "这批更轻松。",
                        "",
                        List.of(new Choice(40, List.of("方向不同"), List.of(), List.of())))),
                new NoResearch());
        RecommendationProfile open = new RecommendationProfile(
                4, 0, BigDecimal.ZERO, BggGameType.ALL, InteractionPreference.ANY);

        var response = fixture.agent.converse(new ConversationRequest(
                open,
                "这几个太重了，换轻松一点",
                List.of(10, 20),
                List.of(new DialogueMessage("user", "这几个太重了，换轻松一点")),
                null), "zh-CN");

        assertThat(response.games()).extracting(game -> game.game().ranking().bggId()).doesNotContain(10, 20);
        assertThat(response.harness().modelCalls()).isLessThanOrEqualTo(2);
        assertThat(response.harness().catalogCalls()).isLessThanOrEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isLessThanOrEqualTo(1);
    }

    @Test
    void turnsAUserCritiqueIntoCandidateDiscoveryAndThenResearchesTheDiscoveredFit() {
        AtomicInteger discoveryCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch tools = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                assertThat(request.signals()).singleElement().satisfies(signal -> {
                    assertThat(signal.term()).isEqualTo("narrative exploration at the table");
                    assertThat(signal.source()).isEqualTo(FeatureSource.EXPERIENCE);
                });
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                40,
                                "Game 40",
                                "体验资料强调了叙事推进和探索抉择",
                                List.of(1))),
                        List.of(new Source(1, "Review", "https://review.example/game-40", "review.example"))));
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("candidate discovery already returned source-grounded fit evidence");
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("上一批过于干燥，开始偏向有叙事推进和探索感的体验", List.of()),
                "我会按你的反馈换一个检索方向。",
                "这次的叙事浓度更接近吗？",
                true,
                "核对候选是否真的有叙事推进与探索抉择",
                new RetrievalPlan(
                        List.of(BggGameType.THEMATIC),
                        List.of(new FeatureConstraint(
                                "narrative exploration at the table",
                                FeatureMode.PREFERRED,
                                FeatureSource.EXPERIENCE,
                                "更有叙事和探索感")),
                        true));
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "我避开了上一批偏干的方向，并核对了实际体验。",
                        "这次的叙事浓度更接近吗？",
                        List.of(new Choice(40, List.of("可能更接近你修正后的偏好"), List.of(), List.of())))),
                tools);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "上一批太干了，我想要更有叙事和探索感的",
                List.of(10, 20),
                List.of(new DialogueMessage("user", "上一批太干了，我想要更有叙事和探索感的")),
                null), "zh-CN");

        assertThat(discoveryCalls).hasValue(1);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains("DISCOVER_CANDIDATES", "LOOKUP_BGG_CANDIDATES", "RESEARCH_GAME_FIT");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(40);
            assertThat(game.reasons()).anySatisfy(reason -> {
                assertThat(reason.kind()).isEqualTo(ReasonKind.WEB_RESEARCH);
                assertThat(reason.text()).contains("叙事推进");
            });
        });
    }

    @Test
    void preservesAnUnmappedQualitativeRequestSoDiscoveryCanReachBeyondTheTopRankPool() {
        AtomicInteger discoveryCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch tools = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveryCalls.incrementAndGet();
                assertThat(request.signals()).singleElement().satisfies(signal -> {
                    assertThat(signal.term()).isEqualTo("区控");
                    assertThat(signal.source()).isEqualTo(FeatureSource.USER_EXPRESSION);
                });
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                60,
                                "Game 60",
                                "资料将它列为区域控制游戏",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Area-control games",
                                "https://review.example/area-control",
                                "review.example"))));
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("candidate discovery already returned source-grounded fit evidence");
            }
        };
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(4, 120, null, null, null),
                new UserModel("四人、两小时左右的竞争游戏", List.of()),
                "我会按人数和时长找候选。",
                "你更在意冲突强度还是规则量？",
                false,
                "",
                RetrievalPlan.empty());
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> {
                    throw new AssertionError("verified discovery must not pay for a second model call");
                },
                tools);

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "推荐一些适合4人、2小时左右的区控游戏",
                List.of(),
                List.of(new DialogueMessage("user", "推荐一些适合4人、2小时左右的区控游戏")),
                null), "zh-CN");

        assertThat(discoveryCalls).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "DISCOVER_CANDIDATES",
                        "LOOKUP_BGG_CANDIDATES",
                        "RESEARCH_GAME_FIT",
                        "RANK_STRUCTURED_CANDIDATES")
                .doesNotContain("COMPOSE_RECOMMENDATIONS");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(60);
            assertThat(game.game().details().mechanics()).contains("Area Control");
            assertThat(game.reasons()).anySatisfy(reason -> {
                assertThat(reason.kind()).isEqualTo(ReasonKind.WEB_RESEARCH);
                assertThat(reason.text()).contains("区域控制");
            });
        });
    }

    @Test
    void reportsTruthfulProgressInExecutionOrder() {
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(4, 120, null, null, null),
                new UserModel("四人策略局", List.of()),
                "我来找候选。",
                "哪个方向更接近？",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "先看这一款。",
                        "哪个方向更接近？",
                        List.of(new Choice(10, List.of(), List.of(), List.of())))),
                new NoResearch());
        List<BoardGameRecommendationAgent.ProgressStage> stages = new ArrayList<>();

        fixture.agent.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "推荐四人策略游戏",
                        List.of(),
                        List.of(new DialogueMessage("user", "推荐四人策略游戏")),
                        null),
                "zh-CN",
                update -> stages.add(update.stage()));

        assertThat(stages).containsExactly(
                BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST,
                BoardGameRecommendationAgent.ProgressStage.SEARCHING_BGG_CATALOG,
                BoardGameRecommendationAgent.ProgressStage.COMPOSING_RESPONSE);
    }

    @Test
    void keepsTwelveDialogueRoundsWhileApplyingBudgetsPerTurn() {
        AtomicReference<BoardGameRecommendationAdvisor.PlanningRequest> captured = new AtomicReference<>();
        Plan ask = new Plan(
                DialogueAct.ASK,
                new PreferencePatch(null, null, null, null, null),
                new UserModel("持续修正偏好", List.of()),
                "我还在跟着你的反馈调整。",
                "这次最想改变上一批的哪一点？",
                false,
                "");
        Fixture fixture = new Fixture(request -> {
            captured.set(request);
            return Optional.of(ask);
        }, request -> Optional.empty(), new NoResearch());
        List<DialogueMessage> transcript = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new DialogueMessage(index % 2 == 0 ? "user" : "assistant", "turn-" + index))
                .toList();

        fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(), "current-turn", List.of(), transcript, null), "zh-CN");

        assertThat(captured.get().transcript()).hasSize(24);
        assertThat(captured.get().transcript().getFirst().text()).isEqualTo("turn-7");
        assertThat(captured.get().transcript().getLast().text()).isEqualTo("current-turn");
    }

    @Test
    void simulatesAnExperiencedCoupleWhoseNuancedTasteStaysInTheSoftUserModel() {
        Plan plan = new Plan(
                DialogueAct.RECOMMEND,
                new PreferencePatch(2, 90, null, null, null),
                new UserModel(
                        "两人局，想要强互动，但不希望体验只剩直接攻击",
                        List.of(new PreferenceHypothesis(
                                "可能偏好通过抢位或竞速产生的间接互动",
                                Confidence.MEDIUM,
                                "强互动但不要纯打架"))),
                "这个条件很清楚，我直接给你候选。",
                "你更喜欢读对手，还是共同解题？",
                false,
                "");
        Fixture fixture = new Fixture(
                request -> Optional.of(plan),
                request -> Optional.of(new Slate(
                        "我先避开纯攻击导向。",
                        "你更喜欢读对手，还是共同解题？",
                        List.of(new Choice(
                                30,
                                List.of("可能用空间争夺提供你要的互动，而不是纯粹互相攻击"),
                                List.of(),
                                List.of())))),
                new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(),
                "两个人，90 分钟，想要强互动但不要纯打架",
                List.of(),
                List.of(new DialogueMessage("user", "两个人，90 分钟，想要强互动但不要纯打架")),
                null), "zh-CN");

        assertThat(response.profile().players()).isEqualTo(2);
        assertThat(response.profile().maxMinutes()).isEqualTo(90);
        assertThat(response.profile().interaction()).isEqualTo(InteractionPreference.ANY);
        assertThat(response.userModel().summary()).contains("不希望体验只剩直接攻击");
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().ranking().bggId()).isEqualTo(30);
            assertThat(game.reasons()).anyMatch(reason -> reason.kind() == ReasonKind.PREFERENCE_INFERENCE);
        });
    }

    @Test
    void simulatesProviderFailureAndStillReturnsGroundedCatalogResults() {
        Fixture fixture = new Fixture(
                request -> {
                    throw new IllegalStateException("provider timeout");
                },
                request -> {
                    throw new AssertionError("composition must not run after planning failure");
                },
                new NoResearch());

        var response = fixture.agent.converse(new ConversationRequest(
                RecommendationProfile.empty(), "4 人，60 分钟，想玩合作游戏"), "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.mode()).isEqualTo(DecisionMode.DETERMINISTIC);
        assertThat(response.harness().fallbackUsed()).isTrue();
        assertThat(response.games()).isNotEmpty().allSatisfy(game ->
                assertThat(game.reasons()).allMatch(reason -> reason.kind() == ReasonKind.BGG_FACT));
    }

    @Test
    void rejectsAnUnboundedPreviouslyShownSet() {
        Fixture fixture = new Fixture(request -> Optional.empty(), request -> Optional.empty(), new NoResearch());
        List<Integer> excluded = java.util.stream.IntStream.rangeClosed(1, 61).boxed().toList();

        assertThatThrownBy(() -> fixture.agent.converse(
                        new ConversationRequest(RecommendationProfile.empty(), "", excluded), "zh-CN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sixty positive ids");
    }

    private static final class Fixture {
        private final MemoryRepository repository = new MemoryRepository();
        private final BoardGameRecommendationAgent agent;

        private Fixture(
                Function<BoardGameRecommendationAdvisor.PlanningRequest, Optional<Plan>> planning,
                Function<BoardGameRecommendationAdvisor.CompositionRequest, Optional<Slate>> composition,
                BoardGameRecommendationWebResearch research) {
            this(planning, composition, research, disabledCandidateModel());
        }

        private Fixture(
                Function<BoardGameRecommendationAdvisor.PlanningRequest, Optional<Plan>> planning,
                Function<BoardGameRecommendationAdvisor.CompositionRequest, Optional<Slate>> composition,
                BoardGameRecommendationWebResearch research,
                BoardGameRecommendationCandidateModel candidateModel) {
            var advisor = new BoardGameRecommendationAdvisor() {
                @Override
                public Optional<Plan> plan(PlanningRequest request) {
                    return planning.apply(request);
                }

                @Override
                public Optional<Slate> compose(CompositionRequest request) {
                    return composition.apply(request);
                }
            };
            var service = new BggRankedCatalogService(repository, new FakeBgg());
            var properties = new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.66"));
            var recommendationTools = new BoardGameRecommendationTools(service, research);
            var candidateAgent = new BoardGameRecommendationCandidateAgent(
                    candidateModel,
                    recommendationTools,
                    new ObjectMapper());
            agent = new BoardGameRecommendationAgent(
                    recommendationTools,
                    new BoardGamePreferenceDialogue(),
                    new BoardGameRecommendationQueryCoverage(),
                    candidateAgent,
                    new BoardGameRecommendationSelector(properties),
                    advisor,
                    properties);
        }

        private static BoardGameRecommendationCandidateModel disabledCandidateModel() {
            return new BoardGameRecommendationCandidateModel() {
                @Override
                public boolean configured() {
                    return false;
                }

                @Override
                public Turn next(Request request) {
                    throw new AssertionError("disabled native candidate model must not run");
                }
            };
        }
    }

    private static final class NoResearch implements BoardGameRecommendationWebResearch {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public Optional<Research> research(Request request) {
            throw new AssertionError("disabled research must not run");
        }
    }

    private static final class MemoryRepository implements BggRankedCatalogRepository {
        private final List<Query> queries = new ArrayList<>();
        private final List<Integer> focusedIds = new ArrayList<>();

        @Override
        public Optional<Snapshot> findSnapshot() {
            return Optional.of(new Snapshot(
                    Instant.parse("2026-08-08T08:00:00Z"),
                    LocalDate.parse("2026-08-08"),
                    179_737,
                    "a".repeat(64)));
        }

        @Override
        public Page find(Query query) {
            queries.add(query);
            return new Page(140_217, query.page(), query.size(), rankedGames());
        }

        @Override
        public List<RankedGame> findByIds(List<Integer> ids) {
            focusedIds.addAll(ids);
            Map<Integer, RankedGame> byId = rankedGames().stream()
                    .collect(java.util.stream.Collectors.toMap(RankedGame::bggId, Function.identity()));
            byId.put(70, ranked(70, 70, "7.8"));
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }

        private List<RankedGame> rankedGames() {
            return List.of(
                    ranked(10, 1, "8.8"),
                    ranked(11, 2, "8.7"),
                    ranked(20, 3, "8.6"),
                    ranked(30, 4, "8.5"),
                    ranked(40, 5, "8.4"),
                    ranked(50, 6, "8.3"),
                    ranked(60, 7, "8.2"));
        }

        private RankedGame ranked(int id, int rank, String rating) {
            return new RankedGame(
                    id,
                    "Game " + id,
                    2025,
                    rank,
                    new BigDecimal(rating),
                    new BigDecimal(rating).add(new BigDecimal("0.2")),
                    10_000 - id,
                    false,
                    Map.of(BggGameType.STRATEGY, rank));
        }

        @Override
        public void stage(UUID importId, List<RankedGame> games) {}

        @Override
        public void publish(UUID importId, Snapshot snapshot) {}
    }

    private static final class FakeBgg implements BoardGameGeekCatalog {
        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            return List.of();
        }

        @Override
        public List<GameMatch> exactMatches(String query) {
            return List.of();
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> hotGameDetails() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> gameDetails(List<Integer> bggIds) {
            return bggIds.stream().map(this::details).toList();
        }

        private DiscoveryGame details(int id) {
            return switch (id) {
                case 10 -> game(id, 2, 4, 75, "3.0", List.of("Strategy"), List.of("Cooperative Game", "Deck Building"));
                case 11 -> game(id, 2, 4, 80, "3.1", List.of("Strategy"), List.of("Cooperative Game", "Deck Building"));
                case 20 -> game(id, 1, 5, 60, "2.4", List.of("Animals"), List.of("Card Drafting"));
                case 30 -> game(id, 1, 2, 45, "2.0", List.of("Abstract"), List.of("Grid Movement"));
                case 40 -> game(id, 3, 6, 150, "3.0", List.of("Thematic"), List.of("Team-Based Game"));
                case 60 -> game(id, 1, 4, 120, "3.2", List.of("Science Fiction"), List.of("Area Control"));
                case 70 -> game(id, 2, 5, 100, "2.8", List.of("Economic"), List.of("Auction"));
                default -> game(id, 2, 5, 90, "2.8", List.of("Economic"), List.of("Worker Placement"));
            };
        }

        private DiscoveryGame game(
                int id,
                int minPlayers,
                int maxPlayers,
                int minutes,
                String weight,
                List<String> categories,
                List<String> mechanics) {
            return new DiscoveryGame(
                    id,
                    id,
                    "Game " + id,
                    id == 10 ? "合作十号" : "",
                    2025,
                    "https://example.test/" + id + ".jpg",
                    minPlayers,
                    maxPlayers,
                    minutes,
                    new BigDecimal("8.5"),
                    new BigDecimal(weight),
                    categories,
                    mechanics,
                    Math.max(15, minutes - 20),
                    minutes,
                    10,
                    10,
                    id == 10 ? "Best with 4 players" : "",
                    "Recommended with 1–5 players",
                    2,
                    100,
                    List.of("Family " + id),
                    List.of("Designer " + id),
                    List.of("Publisher " + id));
        }

        @Override
        public GameDetails game(int bggId) {
            return new GameDetails(
                    bggId,
                    "Game " + bggId,
                    "玩家在回合中派遣代理人，并通过卡牌构筑强化后续行动。",
                    "https://example.test/" + bggId + "-thumb.jpg",
                    2025,
                    1,
                    5,
                    60,
                    10,
                    "https://example.test/" + bggId + ".jpg",
                    new BigDecimal("8.5"),
                    new BigDecimal("2.4"),
                    List.of("Strategy"),
                    List.of("Card Drafting", "Worker Placement"),
                    List.of("Designer " + bggId),
                    List.of("Publisher " + bggId),
                    bggId == 20 ? List.of("二十号游戏") : List.of());
        }
    }
}
