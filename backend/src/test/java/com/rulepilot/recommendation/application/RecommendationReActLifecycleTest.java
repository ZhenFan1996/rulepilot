package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressAction;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocusKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TurnCheckpoint;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecommendationReActLifecycleTest {

    @Test
    void preservesExplicitOpeningFactsWhileDiscardingValuesProposedByTheQuestion() throws Exception {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "opening-question",
                BoardGameRecommendationAgent.ASK_TOOL,
                """
                {
                  "question":"今晚五个人聚会，想从合作游戏换换口味——你更倾向哪种方向？",
                  "options":["轻松热闹的派对互动","更烧脑的策略对抗","谈判与彼此留一手"],
                  "preferenceUpdates":[
                    {"field":"type","value":"PARTY","evidence":"U1"},
                    {"field":"type","value":"STRATEGY","evidence":"U1"},
                    {"field":"type","value":"PARTY","evidence":"U1"},
                    {"field":"playerCount","value":5,"evidence":"U1"}
                  ]
                }
                """)));
        RecordingCatalog catalog =
                new RecordingCatalog(game(497, "Waiting Table", "等待桌", "This game must not be read."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "嗨，今晚五个人聚会，最近合作玩得有点腻，但我还没想清楚换什么方向。你会先怎么帮我挑？"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.profile().playerCount()).satisfies(range -> {
            assertThat(range.exact()).isTrue();
            assertThat(range.minimum()).isEqualTo(5);
        });
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains(
                        "UPDATE_PREFERENCES",
                        "IGNORED_INVALID_PREFERENCE_UPDATE:PREFERENCE_FIELD_INVALID",
                        "ASK_USER")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION:"));
        assertThat(catalog.searches).hasValue(0);

        var askSchema = new ObjectMapper().readTree(model.requests.getFirst().tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.ASK_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(askSchema.path("properties").path("preferenceUpdates").path("type").asText())
                .isEqualTo("object");
        assertThat(askSchema.path("properties").path("preferenceUpdates").path("properties").has("playerCount"))
                .isTrue();
        assertThat(askSchema.path("properties").path("preferenceUpdates").path("properties").has("type"))
                .isFalse();

        loop.stopBoundedCalls();
    }

    @Test
    void publishesCandidateClaimsAfterOneTypedRetrievalDecision() throws Exception {
        ScriptedModel model = new ScriptedModel(List.of(action(
                        "browse-grounded",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        """
                        {
                          "limit":1,
                          "requestedCount":1,"requestedCountBasis":"U1",
                          "preferenceUpdates":{"evidence":"U1","playerCount":4,"durationMinutes":{"maximum":60}}
                        }
                        """)));
        RecommendationReActLoop loop = loop(
                model,
                new BoardGameRecommendationTools(
                        new RecordingCatalog(game(498, "Four Voices", "四方声", "Players choose together.")),
                        configuredResearchThatMustNotRun()));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人，最多一小时，直接挑一款互动不断线的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(model.requests).hasSize(1);
        assertThat(response.assistantMessage())
                .contains("1 款", "卡片里列出了匹配点");
        assertThat(response.games()).singleElement().satisfies(game ->
                assertThat(game.replyParts()).hasSize(2).allSatisfy(part -> {
                    assertThat(part.role()).isEqualTo(BoardGameRecommendationAgent.ReplyPartRole.WHY_FIT);
                    assertThat(part.claim().type())
                            .isEqualTo(com.rulepilot.recommendation.CandidateClaim.Type.CONSTRAINT_FIT);
                    assertThat(part.claim().evidence()).singleElement().satisfies(evidence ->
                            assertThat(evidence.bggId()).isEqualTo(498));
                }));
        assertThat(response.harness().actions())
                .contains("UPDATE_PREFERENCES", "RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION:")
                        || action.contains("NARRATIVE"));
        var browseSchema = new ObjectMapper().readTree(model.requests.getFirst().tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema());
        assertThat(browseSchema.path("properties").path("preferenceUpdates").path("type").asText())
                .isEqualTo("object");
        var typePreferenceSchema = browseSchema.path("properties")
                .path("preferenceUpdates")
                .path("properties")
                .path("type");
        assertThat(typePreferenceSchema.path("description").asText()).contains("BGG product class");
        assertThat(typePreferenceSchema.path("anyOf").get(0).path("enum"))
                .noneMatch(value -> "COMPETITIVE".equals(value.asText()));
        assertThat(browseSchema.path("properties").path("preferenceUpdates").path("properties").path("interaction")
                        .toString())
                .contains("COMPETITIVE");
        assertThat(browseSchema.path("properties").has("candidateUse")).isFalse();
        loop.stopBoundedCalls();
    }

    @Test
    void patchesOneRangeBoundWithoutErasingTheOtherConfirmedBound() {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "shorter-window",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"preferenceUpdates\":[{\"field\":\"durationMinutes\",\"value\":{\"maximum\":45},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")));
        RecommendationReActLoop loop = loop(
                model,
                new RecordingCatalog(game(498, "Short Window", "短时段", "A bounded fixture.")));
        RecommendationProfile current = new RecommendationProfile(
                null,
                ConstraintRange.hard(30, 60, "之前确认 30 到 60 分钟。", 1),
                null,
                BggGameType.ALL,
                BoardGameRecommendationAgent.InteractionPreference.ANY);

        var response = loop.converse(
                new ConversationRequest(current, "最多改成 45 分钟，其余条件保留。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range -> {
            assertThat(range.minimum()).isEqualTo(30);
            assertThat(range.maximum()).isEqualTo(45);
        });
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        loop.stopBoundedCalls();
    }

    @Test
    void ignoresEvidenceOwnedCategoricalClearsThatAreAlreadyAtTheirOpenDefaults() {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "open-categories",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\","
                        + "\"preferenceUpdates\":["
                        + "{\"field\":\"type\",\"value\":null,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                        + "{\"field\":\"interaction\",\"value\":null,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")));
        RecordingCatalog catalog = new RecordingCatalog(
                game(497, "Open Defaults", "开放默认", "A verified fixture with no categorical gate."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "类型和合作或对抗都不限，请直接给我一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().type()).isEqualTo(BggGameType.ALL);
        assertThat(response.profile().interaction())
                .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
        assertThat(response.games()).hasSize(1);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("SEARCH_BGG_CATALOG", "RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION"));

        loop.stopBoundedCalls();
    }

    @Test
    void recordsOneLowCardinalityWorkflowObservationAroundTheCompleteReactTurn() {
        var registry = ObservationRegistry.create();
        var stopped = new AtomicReference<RecordedObservation>();
        var stageOutcomes = new ArrayList<String>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if ("rulepilot.recommendation.workflow".equals(context.getName())) {
                    var outcome = context.getLowCardinalityKeyValue("outcome");
                    stopped.set(new RecordedObservation(
                            context.getName(),
                            context.getContextualName(),
                            outcome == null ? null : outcome.getValue()));
                } else if ("rulepilot.recommendation.stage".equals(context.getName())) {
                    stageOutcomes.add(context.getLowCardinalityKeyValue("stage").getValue()
                            + ":"
                            + context.getLowCardinalityKeyValue("action").getValue()
                            + ":"
                            + context.getLowCardinalityKeyValue("outcome").getValue());
                }
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        ScriptedModel model = new ScriptedModel(List.of(action(
                "reply",
                BoardGameRecommendationAgent.REPLY_TOOL,
                "{\"playerReply\":\"我先根据你已经说清楚的部分回答。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(499, "Unused", "未使用", "Unused."));
        RecommendationReActLoop loop = loop(model, catalog, registry);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "先聊聊，不用查目录。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(stopped).hasValue(new RecordedObservation(
                "rulepilot.recommendation.workflow", "recommendation-react", "conversation"));
        assertThat(stageOutcomes).containsExactly(
                "understanding_request:understand_request:completed",
                "selecting_tools:choose_next_action:completed",
                "composing_response:reply_to_user:completed");
        loop.stopBoundedCalls();
    }

    @Test
    void recordsOneDecisionAndActionBeforeImmediateApplicationOwnedPublication()
            throws Exception {
        var registry = ObservationRegistry.create();
        var stopped = new ArrayList<RecordedOperation>();
        var stopOrder = new ArrayList<String>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if ("rulepilot.recommendation.operation".equals(context.getName())) {
                    stopped.add(new RecordedOperation(
                            context.getLowCardinalityKeyValue("stage").getValue(),
                            context.getLowCardinalityKeyValue("action").getValue(),
                            context.getLowCardinalityKeyValue("outcome").getValue(),
                            context.getLowCardinalityKeyValue("recovered").getValue()));
                    stopOrder.add("operation:" + stopped.getLast().stage());
                } else if ("rulepilot.recommendation.workflow".equals(context.getName())) {
                    stopOrder.add("workflow:" + context.getLowCardinalityKeyValue("outcome").getValue());
                }
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        ScriptedModel model = new ScriptedModel(List.of(action(
                "browse",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":6,\"requestedCount\":2,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(
                model,
                new RecordingCatalog(
                        game(101, "One", "一", "One."),
                        game(102, "Two", "二", "Two."),
                        game(103, "Three", "三", "Three."),
                        game(104, "Four", "四", "Four."),
                        game(105, "Five", "五", "Five."),
                        game(106, "Six", "六", "Six.")),
                registry);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "请从较大的候选池里给我两张卡。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101, 102);
        assertThat(response.assistantMessage()).contains("2 款", "卡片里列出了匹配点");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions()).contains("RECOMMEND_GAMES")
                .noneMatch(action -> action.contains("NARRATIVE"))
                .noneMatch(action -> action.startsWith("UNAVAILABLE:"));
        assertThat(stopped).containsExactly(
                new RecordedOperation("decision_model", "choose_next_action", "completed", "false"),
                new RecordedOperation(
                        "typed_action", BoardGameRecommendationAgent.BROWSE_TOOL, "completed", "false"));
        assertThat(stopOrder).containsExactly(
                "operation:decision_model",
                "operation:typed_action",
                "workflow:recommendations");
        loop.stopBoundedCalls();
    }

    @Test
    void usesTheConfiguredCardCountWhenTheTypedBrowseActionDeclaresTheDefaultWithoutEvidence() {
        var registry = ObservationRegistry.create();
        var stopped = new ArrayList<RecordedOperation>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if (!"rulepilot.recommendation.operation".equals(context.getName())) return;
                stopped.add(new RecordedOperation(
                        context.getLowCardinalityKeyValue("stage").getValue(),
                        context.getLowCardinalityKeyValue("action").getValue(),
                        context.getLowCardinalityKeyValue("outcome").getValue(),
                        context.getLowCardinalityKeyValue("recovered").getValue()));
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        ScriptedModel model = new ScriptedModel(List.of(action(
                "browse",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":6,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\"}")));
        RecommendationReActLoop loop = loop(
                model,
                new RecordingCatalog(
                        game(101, "One", "一", "One."),
                        game(102, "Two", "二", "Two."),
                        game(103, "Three", "三", "Three."),
                        game(104, "Four", "四", "Four."),
                        game(105, "Five", "五", "Five."),
                        game(106, "Six", "六", "Six.")),
                registry);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我几张候选卡。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101, 102, 103);
        assertThat(response.shortfall()).isNull();
        assertThat(stopped).containsExactly(
                new RecordedOperation("decision_model", "choose_next_action", "completed", "false"),
                new RecordedOperation(
                        "typed_action", BoardGameRecommendationAgent.BROWSE_TOOL, "completed", "false"));
        loop.stopBoundedCalls();
    }

    @Test
    void rejectsANonDefaultValueClaimingProductDefaultBeforeReadingTheCatalog() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-product-default",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3,\"requestedCount\":2,\"requestedCountBasis\":\"PRODUCT_DEFAULT\"}"),
                action(
                        "finish-after-invalid-default",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"默认数量没有通过契约校验，所以这次没有读取目录。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(114, "Never Read", "不会读取", "Unused."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我一些候选。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REQUESTED_COUNT_DEFAULT_INVALID", "REPLY_TO_USER");
        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnOlderTurnAsTheSourceOfTheCurrentCardCount() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "stale-count-evidence",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":3,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}"),
                action(
                        "finish-after-stale-count",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"你已经撤回上一轮的数量要求，我不会继续沿用。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(115, "Stale Count", "旧数量", "Unused."));
        RecommendationReActLoop loop = loop(model, catalog);
        String current = "刚才的一款不算了，数量按默认来。";

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        current,
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "只推荐一款。"),
                                new DialogueMessage("assistant", "好。"),
                                new DialogueMessage("user", current)),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REQUESTED_COUNT_EVIDENCE_NOT_CURRENT", "REPLY_TO_USER");
        assertThat(model.requests.getFirst().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .inputSchema())
                .contains("\"enum\":[\"PRODUCT_DEFAULT\",\"U2\"]");
        loop.stopBoundedCalls();
    }

    @Test
    void retrievesEnoughEligibleCandidatesForTheTypedPublicationCount() {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "browse",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":5,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(
                model,
                new RecordingCatalog(
                        game(101, "One", "一", "One."),
                        game(102, "Two", "二", "Two."),
                        game(103, "Three", "三", "Three."),
                        game(104, "Four", "四", "Four."),
                        game(105, "Five", "五", "Five.")));

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "请给我五张候选卡。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(101, 102, 103, 104, 105);
        assertThat(response.shortfall()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        loop.stopBoundedCalls();
    }

    @Test
    void scansPastTheModelFacingCandidateWindowBeforeClaimingAFalseShortfall() {
        List<Game> games = java.util.stream.IntStream.rangeClosed(101, 110)
                .mapToObj(id -> game(id, "Game " + id, "游戏 " + id, "A catalog fixture."))
                .toList();
        AtomicInteger requestedCatalogWindow = new AtomicInteger();
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(
                    BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                requestedCatalogWindow.set(filters.maximum());
                return new CandidateSet(
                        games.size(), games.stream().limit(filters.maximum()).toList());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return games.stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public int gameCount() {
                return games.size();
            }
        };
        BoardGameRecommendationWebResearch noResearch = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(action(
                "browse-after-shown",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":2,\"requestedCount\":2,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(
                model, new BoardGameRecommendationTools(catalog, noResearch));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "前八款都看过了，再给我两款不同的。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(101, 102, 103, 104, 105, 106, 107, 108)),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(requestedCatalogWindow).hasValue(20);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(109, 110);
        assertThat(response.shortfall()).isNull();
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        loop.stopBoundedCalls();
    }

    @Test
    void pagesWithinOneTypedCatalogActionUntilAHardGateEligibleCandidateIsFound() {
        List<Game> partyGames = java.util.stream.IntStream.rangeClosed(101, 120)
                .mapToObj(id -> game(id, "Party " + id, "聚会 " + id, "A party fixture."))
                .toList();
        Game strategyBase = game(121, "Strategy 121", "策略 121", "A strategy fixture.");
        Game strategyGame = new Game(
                new Ranking(
                        strategyBase.ranking().bggId(),
                        strategyBase.ranking().sourceName(),
                        strategyBase.ranking().publicationYear(),
                        strategyBase.ranking().overallRank(),
                        strategyBase.ranking().bayesAverage(),
                        strategyBase.ranking().averageRating(),
                        strategyBase.ranking().usersRated(),
                        List.of(BggGameType.STRATEGY)),
                strategyBase.details());
        List<Game> games = new ArrayList<>(partyGames);
        games.add(strategyGame);
        List<Integer> observedOffsets = new ArrayList<>();
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(
                    BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                observedOffsets.add(filters.offset());
                return new CandidateSet(
                        games.size(),
                        games.stream()
                                .skip(filters.offset())
                                .limit(filters.maximum())
                                .toList(),
                        filters.offset() + filters.maximum() >= games.size());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return games.stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public int gameCount() {
                return games.size();
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(action(
                "browse-strategy",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(model, new BoardGameRecommendationTools(catalog, noResearch()));
        RecommendationProfile profile = new RecommendationProfile(
                null,
                null,
                null,
                BggGameType.STRATEGY,
                BoardGameRecommendationAgent.InteractionPreference.ANY);

        var response = loop.converse(
                new ConversationRequest(profile, "请给我一款策略游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(observedOffsets).containsExactly(0, 20);
        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(121);
        assertThat(response.shortfall()).isNull();
        assertThat(response.harness().modelCalls()).isOne();
        loop.stopBoundedCalls();
    }

    @Test
    void keepsAResolvedComparisonReferenceOutOfTheSelectableCatalogSlots() {
        Game reference = game(131, "Reference Game", "参照游戏", "The player-named comparison anchor.");
        Game candidate = game(132, "Distinct Candidate", "不同候选", "A distinct selectable candidate.");
        List<Game> games = List.of(reference, candidate);
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(
                    BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                return new CandidateSet(
                        games.size(),
                        games.stream()
                                .skip(filters.offset())
                                .limit(filters.maximum())
                                .toList());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return games.stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public List<Game> resolveLocalReferenceTitle(String title) {
                return "Reference Game".equals(title) ? List.of(reference) : List.of();
            }

            @Override
            public int gameCount() {
                return games.size();
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "resolve-reference",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Reference Game\",\"purpose\":\"COMPARISON_REFERENCE\",\"evidence\":\"U1\"}"),
                action(
                        "browse-distinct-candidate",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(model, new BoardGameRecommendationTools(catalog, noResearch()));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "以 Reference Game 为参照，推荐一款不同的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(132);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsSubsequence("RESOLVE_BGG_REFERENCE", "SEARCH_BGG_CATALOG", "RECOMMEND_GAMES");
        loop.stopBoundedCalls();
    }

    @Test
    void acceptsADocumentedCanonicalDecimalPreferredIdWithoutAnotherModelRepair() {
        Game first = game(121, "First Choice", "第一款", "A comparison fixture.");
        Game second = game(122, "Second Choice", "第二款", "Another comparison fixture.");
        ScriptedModel model = new ScriptedModel(List.of(action(
                "compare",
                BoardGameRecommendationAgent.COMPARE_TOOL,
                "{\"candidateBggIds\":[121,122],\"subjects\":[\"durationMinutes\"],"
                        + "\"preferredBggId\":\"121\","
                        + "\"internalEvidenceIds\":[\"B121:durationMinutes\",\"B122:durationMinutes\"],"
                        + "\"playerReply\":\"两款时长都已核对；如果今晚要直接做决定，我会先选第一款。\"}")));
        RecommendationReActLoop loop = loop(model, new RecordingCatalog(first, second));

        var response = loop.converseValidated(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "比较这两款并直接告诉我怎么选。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(121, 122),
                        List.of(first, second)),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().actions()).containsExactly("COMPARE_CANDIDATES");
        loop.stopBoundedCalls();
    }

    @Test
    void publishesOnlyCanonicalTitlesThatSatisfyTheCurrentTurnLiteralBoundary() {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "literal-title-boundary",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                """
                {
                  "purpose":"SELECTABLE_CARDS",
                  "titleConstraint":{"operator":"CONTAINS","value":"  HARBOR  "},
                  "evidence":"U1",
                  "requestedCount":2,
                  "requestedCountBasis":"U1",
                  "limit":3
                }
                """)));
        RecordingCatalog catalog = new RecordingCatalog(
                game(601, "Orbit　Harbor", "轨道港", "A title-boundary fixture."),
                game(602, "Harbor Guild", "港口公会", "Another title-boundary fixture."),
                game(603, "Alpine Picnic", "高山野餐", "An unrelated eligible fixture."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "只给我两款标题明确包含 Harbor 的游戏；不要用主题相近但标题不含这个词的游戏补位。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(601, 602);
        assertThat(response.shortfall()).isNull();
        assertThat(catalog.searches).hasValue(1);
        loop.stopBoundedCalls();
    }

    @Test
    void reportsAShortfallInsteadOfFillingALiteralTitleBoundaryWithUnrelatedCards() {
        ScriptedModel model = new ScriptedModel(List.of(action(
                "literal-title-shortfall",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                """
                {
                  "titleConstraint":{"operator":"CONTAINS","value":"Harbor"},
                  "evidence":"U1",
                  "requestedCount":3,
                  "requestedCountBasis":"U1",
                  "limit":3
                }
                """)));
        RecommendationReActLoop loop = loop(
                model,
                new RecordingCatalog(
                        game(611, "Harbor Signals", "港口信号", "A matching fixture."),
                        game(612, "Guild of the Harbor", "港口公会", "Another matching fixture."),
                        game(613, "Forest Signals", "森林信号", "An unrelated eligible fixture.")));

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "请推荐三款标题包含 Harbor 的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(611, 612);
        assertThat(response.shortfall()).satisfies(shortfall -> {
            assertThat(shortfall.requestedCount()).isEqualTo(3);
            assertThat(shortfall.availableCount()).isEqualTo(2);
        });
        loop.stopBoundedCalls();
    }

    @Test
    void rejectsALiteralTitleBoundaryThatCitesAnEarlierUserTurnBeforeCatalogAccess() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "stale-title-evidence",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        """
                        {
                          "titleConstraint":{"operator":"CONTAINS","value":"Harbor"},
                          "evidence":"U1",
                          "requestedCount":2,
                          "requestedCountBasis":"U2"
                        }
                        """),
                action(
                        "transparent-reply",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我没有把旧一轮的标题条件冒充成本轮选择边界。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(
                game(621, "Harbor Archive", "港口档案", "This read must not run."));
        RecommendationReActLoop loop = loop(model, catalog);
        String current = "这一轮不要沿用上一轮的标题筛选，先直接说明你会怎么处理。";

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        current,
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "上一轮只看 Harbor 标题。"),
                                new DialogueMessage("assistant", "明白。"),
                                new DialogueMessage("user", current)),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:TITLE_CONSTRAINT_EVIDENCE_NOT_CURRENT", "REPLY_TO_USER");
        assertThat(catalog.searches).hasValue(0);
        loop.stopBoundedCalls();
    }

    @Test
    void aDirectTargetCannotBypassTheTypedTitleBoundaryEstablishedByAnEarlierAction() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "establish-title-boundary",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        """
                        {
                          "titleConstraint":{"operator":"CONTAINS","value":"Harbor"},
                          "evidence":"U1",
                          "requestedCount":1,
                          "requestedCountBasis":"U1",
                          "limit":2
                        }
                        """),
                action(
                        "unrelated-direct-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        """
                        {
                          "title":"Catan",
                          "purpose":"TARGET_GAME",
                          "evidence":"U1",
                          "playerReply":"错误地把标题不匹配的游戏作为目标。"
                        }
                        """),
                action(
                        "publish-matching-title",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        """
                        {
                          "requestedCount":1,
                          "requestedCountBasis":"U1",
                          "limit":2
                        }
                        """)));
        Game harbor = game(634, "Harbor Signals", "港口信号", "A matching hard-boundary fixture.");
        Game catan = game(635, "Catan", "卡坦岛", "An unrelated direct-target fixture.");
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        when(catalog.searchGames(any(CatalogFilters.class)))
                .thenReturn(new CandidateSet(0, List.of()), new CandidateSet(1, List.of(harbor)));
        when(catalog.resolveLocalReferenceTitle("Catan")).thenReturn(List.of(catan));
        RecommendationReActLoop loop = loop(model, new BoardGameRecommendationTools(catalog, noResearch()));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "只推荐标题包含 Harbor 的游戏；即使想到 Catan，也不能拿它补位。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(634);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:FINAL_TITLE_CONSTRAINT_MISMATCH", "RECOMMEND_GAMES");
        assertThat(response.harness().actions().stream()
                        .filter("RESOLVE_BGG_REFERENCE"::equals)
                        .toList())
                .isEmpty();
        loop.stopBoundedCalls();
    }

    @Test
    void reportsTheValidatedCatalogMechanicBeforeStartingThatRead() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-mechanic-read",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"mechanics\":\"Deck Building\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}"),
                action(
                        "validated-mechanic-read",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"mechanics\":[\"Deck Building\"],\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}")));
        List<ProgressUpdate> progress = new ArrayList<>();
        RecordingCatalog catalog = new RecordingCatalog(game(
                499,
                "Workshop Signals",
                "工坊信号",
                "Four players build their decks around a shared workshop."));
        catalog.beforeSearch = () -> assertThat(progress)
                .anySatisfy(update -> {
                    assertThat(update.phase()).isEqualTo(ProgressPhase.STARTED);
                    assertThat(update.action()).isEqualTo(ProgressAction.BROWSE_BGG_CATALOG);
                    assertThat(update.focus()).satisfies(focus -> {
                        assertThat(focus.kind()).isEqualTo(ProgressFocusKind.CATALOG_MECHANICS);
                        assertThat(focus.values()).containsExactly("Deck Building");
                    });
                });
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人都玩过几次桌游，这次想要牌组构筑感明确、但别太闷的一款。"),
                "zh-CN",
                "player",
                progress::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(catalog.searches).hasValue(1);
        assertThat(progress.stream()
                        .filter(update -> update.phase() == ProgressPhase.STARTED)
                        .filter(update -> update.action() == ProgressAction.BROWSE_BGG_CATALOG)
                        .count())
                .isEqualTo(1);
        assertThat(progress.stream()
                        .filter(update -> update.action() == ProgressAction.BROWSE_BGG_CATALOG)
                        .filter(update -> update.focus() != null)
                        .toList())
                .allSatisfy(update -> {
                    assertThat(update.focus().kind()).isEqualTo(ProgressFocusKind.CATALOG_MECHANICS);
                    assertThat(update.focus().values()).containsExactly("Deck Building");
                });
        assertThat(response.harness().actions()).contains("REJECTED_ACTION:STRING_LIST_INVALID");

        loop.stopBoundedCalls();
    }

    @Test
    void keepsAValidPreferenceSiblingWhenAnotherTypedFieldIsInvalid() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "mixed-invalid-read",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\",\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"playerCount\",\"value\":99,\"evidence\":\"U1\",\"evidenceClassification\":\"INFERRED_GROUP_MEMBER_COUNT\"}]}"),
                action(
                        "transparent-finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我刚才没有可靠地读出完整桌面人数，所以不会把这个猜测写进条件；你可以直接告诉我总人数，我再继续挑。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(500, "Quiet Harbor", "静港", "A calm game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "大约想玩一小时，但我只知道会带朋友来，还没确认最后总共有几个人。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(response.profile().playerCount()).isNull();
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "UPDATE_PREFERENCES",
                        "IGNORED_INVALID_PREFERENCE_UPDATE:PLAYERS_OUT_OF_RANGE",
                        "SEARCH_BGG_CATALOG")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION:"));

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAMalformedDirectHardFieldBeforeAValidPatchCanPublish() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "malformed-direct-player-count",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"playerCount\",\"value\":\"four\",\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "valid-direct-patch",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"preferenceUpdates\":{\"evidence\":\"U1\","
                                + "\"playerCount\":4,\"durationMinutes\":{\"maximum\":60}}}")));
        RecordingCatalog catalog = new RecordingCatalog(
                game(515, "Typed Harbor", "类型港", "A four-player game that finishes within an hour."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人，最多一小时，请直接推荐一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().playerCount().minimum()).isEqualTo(4);
        assertThat(response.profile().durationMinutes().maximum()).isEqualTo(60);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "REJECTED_ACTION:ARGUMENT_OBJECT_REQUIRED",
                        "UPDATE_PREFERENCES",
                        "SEARCH_BGG_CATALOG")
                .doesNotContain("IGNORED_INVALID_PREFERENCE_UPDATE:ARGUMENT_OBJECT_REQUIRED");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnInvalidDirectCategoricalFieldBeforePublishing() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-direct-type",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\",\"preferenceUpdates\":["
                                + "{\"field\":\"type\",\"value\":\"PUZZLE\",\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "valid-direct-type",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\",\"preferenceUpdates\":["
                                + "{\"field\":\"type\",\"value\":\"PARTY\",\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")));
        RecordingCatalog catalog = new RecordingCatalog(
                game(516, "Party Harbor", "聚会港", "A party game for a conversational table."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们明确想找聚会游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "REJECTED_ACTION:GAME_TYPE_INVALID",
                        "RECORD_CONTEXTUAL_PREFERENCE",
                        "SEARCH_BGG_CATALOG")
                .doesNotContain("IGNORED_INVALID_PREFERENCE_UPDATE:GAME_TYPE_INVALID");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnUngroundedDirectConstraintUntilAValidRepairPreservesIt() {
        String invalidBrowse = "{\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,\"evidence\":\"U99\",\"evidenceClassification\":\"DIRECT\"}]}";
        String validBrowse = "{\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("invalid-read-1", BoardGameRecommendationAgent.BROWSE_TOOL, invalidBrowse),
                action("valid-read", BoardGameRecommendationAgent.BROWSE_TOOL, validBrowse)));
        RecordingCatalog catalog = new RecordingCatalog(game(501, "Open Table", "开放桌面", "A social game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "周六我们四个人聚会，两位朋友几乎没玩过桌游；先直接给我一个容易聊起来的选择。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement().satisfies(value -> {
            assertThat(value.game().ranking().bggId()).isEqualTo(501);
            assertThat(value.replyParts()).singleElement().satisfies(part -> {
                assertThat(part.role()).isEqualTo(ReplyPartRole.WHY_FIT);
                assertThat(part.claim().subject()).isEqualTo("playerCount");
                assertThat(part.claim().relation()).isEqualTo(CandidateClaim.Relation.SATISFIED);
                assertThat(part.claim().evidence())
                        .extracting(CandidateObservation::id)
                        .containsExactly("B501:playerCount");
            });
        });
        assertThat(response.profile().playerCount()).satisfies(range -> {
            assertThat(range.exact()).isTrue();
            assertThat(range.minimum()).isEqualTo(4);
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "REJECTED_ACTION:PREFERENCE_EVIDENCE_NOT_GROUNDED",
                        "UPDATE_PREFERENCES",
                        "SEARCH_BGG_CATALOG")
                .doesNotContain(
                        "REUSED_ACTION_ERROR",
                        "IGNORED_INVALID_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_NOT_GROUNDED");

        loop.stopBoundedCalls();
    }

    @Test
    void namesUnexpectedContractFieldsSoADifferentInvalidPayloadCanBeRepaired() throws Exception {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-object-contract",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"limit\":1,\"audience\":\"new players\"}"),
                action(
                        "repaired-object-contract",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"limit\":1}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                520,
                "First Evening",
                "初次相聚",
                "Players make visible choices and discuss them together."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "给第一次见面的朋友直接推荐一款桌游。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:UNEXPECTED_ARGUMENT", "SEARCH_BGG_CATALOG")
                .doesNotContain("REUSED_ACTION_ERROR", "REACT_BUDGET_EXHAUSTED");

        Message contractObservation = model.requests.get(1).messages().stream()
                .filter(message -> message.role() == BoardGameRecommendationModel.Role.TOOL)
                .findFirst()
                .orElseThrow();
        JsonNode error = new ObjectMapper().readTree(contractObservation.content());
        assertThat(error.path("code").asText()).isEqualTo("UNEXPECTED_ARGUMENT");
        assertThat(error.path("unexpectedArguments"))
                .extracting(JsonNode::asText)
                .containsExactly("audience");
        assertThat(error.path("allowedArguments"))
                .extracting(JsonNode::asText)
                .contains("requestedCount", "preferenceUpdates")
                .doesNotContain("audience", "new players");
        assertThat(contractObservation.content()).doesNotContain("new players");

        loop.stopBoundedCalls();
    }

    @Test
    void stopsARepeatedCanonicalContractErrorWithItsEarliestCodeInsteadOfExhaustingBudget() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-contract-first",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"limit\":1,\"rankingPolicy\":\"recent\"}"),
                action(
                        "invalid-contract-reordered",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{ \"rankingPolicy\" : \"recent\", \"requestedCountBasis\" : \"U1\", \"limit\" : 1, \"requestedCount\" : 1 }")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                521,
                "Bounded Retry",
                "有限重试",
                "This fixture must never be searched."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "请直接推荐一款适合今晚的桌游。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains(
                        "REJECTED_ACTION:UNEXPECTED_ARGUMENT",
                        "REUSED_ACTION_ERROR",
                        "REPEATED_DETERMINISTIC_ACTION:UNEXPECTED_ARGUMENT",
                        "UNAVAILABLE:REPEATED_DETERMINISTIC_ACTION:UNEXPECTED_ARGUMENT")
                .doesNotContain("REACT_BUDGET_EXHAUSTED", "UNAVAILABLE:BUDGET_EXHAUSTED");
        assertThat(model.requests).hasSize(2);

        loop.stopBoundedCalls();
    }

    @Test
    void reusesASuccessfulReadWhileKeepingOneBoundedCurrentStateViewAndEveryCallResultPair() {
        String browse = "{\"purpose\":\"IDENTITY_ONLY\",\"designers\":[\"Avery Stone\"],\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}";
        String publicationBrowse = "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":0,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}";
        String userRequest = "先核对 Avery Stone 是否确实是目录游戏的设计师，再从核对过的方向里给我一款四个人第一次见面也不会冷场的桌游。";
        ScriptedModel model = new ScriptedModel(List.of(
                action("read-1", BoardGameRecommendationAgent.BROWSE_TOOL, browse),
                action("read-2", BoardGameRecommendationAgent.BROWSE_TOOL, browse),
                action("publish-read", BoardGameRecommendationAgent.BROWSE_TOOL, publicationBrowse)));
        String longDescription = "Players build a shared garden and talk through each turn. "
                + "Every round adds another gentle choice. ".repeat(120)
                + "TAIL_SHOULD_NOT_REACH_CONTEXT";
        RecordingCatalog catalog = new RecordingCatalog(game(502, "Garden Voices", "花园絮语", longDescription));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        userRequest),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(value -> assertThat(value.game().ranking().bggId()).isEqualTo(502));
        assertThat(catalog.searches).hasValue(2);
        assertThat(response.harness().actions())
                .contains("SEARCH_BGG_CATALOG", "REUSED_READ_OBSERVATION")
                .doesNotContain("REJECTED_REPEATED_ACTION");

        Request decisionAfterDuplicate = model.requests.get(2);
        assertThat(decisionAfterDuplicate.messages()).hasSize(6);
        assertThat(decisionAfterDuplicate.messages().get(3).toolCallId()).isEqualTo("read-1");
        assertThat(decisionAfterDuplicate.messages().get(5).toolCallId()).isEqualTo("read-2");
        List<Message> toolMessages = decisionAfterDuplicate.messages().stream()
                .filter(message -> message.role() == BoardGameRecommendationModel.Role.TOOL)
                .toList();
        assertThat(toolMessages).hasSize(2);
        assertThat(toolMessages.getFirst().content()).doesNotContain("\"turnState\"");
        assertThat(toolMessages.getLast().content())
                .contains("\"turnState\"", "publisherDescription")
                .doesNotContain("\"facts\"", "TAIL_SHOULD_NOT_REACH_CONTEXT");
        assertThat(toolMessages).allSatisfy(message -> assertThat(message.content()).doesNotContain(userRequest));
        assertThat(toolMessages.stream().filter(message -> message.content().contains("\"turnState\"")).count())
                .isEqualTo(1);

        loop.stopBoundedCalls();
    }

    @Test
    void executesOneMateriallyDifferentSupplementAfterAnEmptyReadButReusesAnIdenticalRetry() {
        String strictBrowse = "{\"purpose\":\"SELECTABLE_CARDS\","
                + "\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"limit\":8,"
                + "\"mechanics\":[\"Negotiation\",\"Bluffing\"]}";
        String relaxedBrowse = "{\"purpose\":\"SELECTABLE_CARDS\","
                + "\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"limit\":8,"
                + "\"mechanics\":[\"Negotiation\"]}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("strict-read", BoardGameRecommendationAgent.BROWSE_TOOL, strictBrowse),
                action("identical-retry", BoardGameRecommendationAgent.BROWSE_TOOL, strictBrowse),
                action("relaxed-read", BoardGameRecommendationAgent.BROWSE_TOOL, relaxedBrowse)));
        Game rescued = game(511, "Open Bargain", "开放议价", "Players negotiate openly and may bluff.");
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        List<CatalogFilters> executedFilters = new ArrayList<>();
        when(catalog.searchGames(any(CatalogFilters.class))).thenAnswer(invocation -> {
            CatalogFilters filters = invocation.getArgument(0, CatalogFilters.class);
            executedFilters.add(filters);
            return executedFilters.size() == 1
                    ? new CandidateSet(0, List.of())
                    : new CandidateSet(1, List.of(rescued));
        });
        RecommendationReActLoop loop = loop(
                model,
                new BoardGameRecommendationTools(catalog, noResearch()));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "想找一款可以谈判和虚张声势的桌游；如果严格条件没有结果，可以放宽一个软条件。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(value -> assertThat(value.game().ranking().bggId()).isEqualTo(511));
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().catalogCalls()).isEqualTo(2);
        assertThat(executedFilters)
                .extracting(CatalogFilters::mechanics)
                .containsExactly(
                        List.of("Negotiation", "Bluffing"),
                        List.of("Negotiation"));
        assertThat(response.harness().actions())
                .contains("REUSED_READ_OBSERVATION", "RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("REJECTED_ACTION:")
                        || action.equals("REUSED_ACTION_ERROR"));

        loop.stopBoundedCalls();
    }

    @Test
    void normalizesMultipleSourceOnlyPublicCitationsToUniqueSequentialIndexes() {
        RecommendationReActLoop loop = loop(
                new ScriptedModel(List.of()),
                new RecordingCatalog());
        ConversationRequest request = new ConversationRequest(
                RecommendationProfile.empty(),
                "请说明这个公开活动由谁主办，并给出来源。");
        RecommendationAgentState state = new RecommendationAgentState(
                request,
                System.nanoTime(),
                "player",
                true,
                3);
        state.publicContextSources = List.of(
                new BoardGameRecommendationWebResearch.Source(
                        1,
                        "Organizer announcement",
                        "https://events.example.test/announcement",
                        "events.example.test"),
                new BoardGameRecommendationWebResearch.Source(
                        2,
                        "Venue listing",
                        "https://venue.example.test/listing",
                        "venue.example.test"));
        state.publicContextEvidence.put(
                "P1",
                new BoardGameRecommendationWebResearch.PublicContextEvidence(
                        "P1",
                        BoardGameRecommendationWebResearch.PublicSubjectKind.EVENT,
                        "North Harbor Games Week",
                        "organized by",
                        "Harbor Tabletop Association",
                        "Two public sources identify the event organizer.",
                        List.of(1, 2)));
        state.finalResponsePublicEvidenceIds.add("P1");

        var sources = loop.responseSources(state, List.of(), Set.of());

        assertThat(sources)
                .extracting(BoardGameRecommendationAgent.ResearchSource::index)
                .containsExactly(1, 2)
                .doesNotHaveDuplicates();
        assertThat(sources)
                .extracting(BoardGameRecommendationAgent.ResearchSource::domain)
                .containsExactly("events.example.test", "venue.example.test");

        loop.stopBoundedCalls();
    }

    @Test
    void keepsTransparentReplyAvailableWhenOnlyARestoredCandidateExistsAndCurrentBrowseIsEmpty() {
        Game restored = game(510, "Earlier Harbor", "旧港", "A candidate verified in an earlier turn.");
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "empty-current-browse",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"textQuery\":\"a materially different request\",\"limit\":1,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\"}"),
                action(
                        "transparent-finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"这次目录检索没有找到符合新方向的选择；我不会把上一轮的候选冒充成本轮结果。\"}")));
        RecordingCatalog emptyCatalog = new RecordingCatalog();
        RecommendationReActLoop loop = loop(model, emptyCatalog);

        var response = loop.converseValidated(
                validatedRequest("请按一个完全不同的新方向再找一次；找不到就直接告诉我。", List.of(restored)),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.games()).isEmpty();
        assertThat(emptyCatalog.searches).hasValue(1);
        assertThat(response.harness().actions()).contains("SEARCH_BGG_CATALOG", "REPLY_TO_USER");

        loop.stopBoundedCalls();
    }

    @Test
    void readsAgainWhenAnInterveningActionChangesThePreferenceState() {
        String initialBrowse = "{\"purpose\":\"IDENTITY_ONLY\",\"designers\":[\"Avery Stone\"],\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}";
        String correctedBrowse = "{\"purpose\":\"IDENTITY_ONLY\",\"designers\":[\"Avery Stone\"],\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\","
                + "\"preferenceUpdates\":[{\"field\":\"durationMinutes\","
                + "\"value\":{\"minimum\":null,\"maximum\":60},"
                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}";
        String publicationBrowse = "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":0,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("initial-read", BoardGameRecommendationAgent.BROWSE_TOOL, initialBrowse),
                action("corrected-read", BoardGameRecommendationAgent.BROWSE_TOOL, correctedBrowse),
                action("re-read-after-correction", BoardGameRecommendationAgent.BROWSE_TOOL, initialBrowse),
                action("read-after-correction", BoardGameRecommendationAgent.BROWSE_TOOL, publicationBrowse)));
        RecordingCatalog catalog = new RecordingCatalog(game(
                504,
                "Evening Exchange",
                "晚间交换",
                "Four players exchange clues and complete a round in forty-five minutes."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "先核对 Avery Stone 是否确实是目录游戏的设计师；我们四个人想边聊边玩，刚确认今晚最多只有一小时，再帮我挑一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(catalog.searches).hasValue(4);
        assertThat(response.harness().actions())
                .contains("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE")
                .doesNotContain("REUSED_READ_OBSERVATION");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesOnlyAfterAValidatedReadProducesASlate() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "premature-context-lookup",
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        "{\"bggIds\":[503]}"),
                action(
                        "read-candidate",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                503,
                "Shared Signals",
                "共享信号",
                "Players discuss a shared display before choosing one signal together."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人第一次一起玩，想先看一款能让大家自然讨论的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(value -> assertThat(value.game().ranking().bggId()).isEqualTo(503));
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_UNAVAILABLE_ACTION", "SEARCH_BGG_CATALOG", "RECOMMEND_GAMES")
                .doesNotContain("REUSED_ACTION_ERROR");
        assertThat(model.requests).hasSize(2);

        loop.stopBoundedCalls();
    }

    @Test
    void keepsExplicitResearchAvailableForNamedContextBeforeDirectPublication() {
        Game candidate = game(
                517,
                "Single Research",
                "单次调研",
                "A verified candidate with an attributed research observation.");
        RecordingCatalog catalog = new RecordingCatalog(candidate);
        AtomicInteger researchCalls = new AtomicInteger();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                researchCalls.incrementAndGet();
                return Optional.of(new Research(
                        List.of(new GameResearch(
                                517,
                                List.of(new Observation(
                                        "有玩家报告说新手首局需要一点时间进入节奏。",
                                        List.of(1))))),
                        List.of(new Source(
                                1,
                                "Single Research 玩家报告",
                                "https://reports.example.test/single-research",
                                "reports.example.test"))));
            }
        };
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "resolve-named-context",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Single Research\",\"purpose\":\"DISCUSSION_SUBJECT\",\"evidence\":\"U1\"}"),
                action(
                        "research-once",
                        BoardGameRecommendationAgent.RESEARCH_TOOL,
                        "{\"bggIds\":[517],\"question\":\"新玩家第一次同桌时是否容易进入状态？\"}"),
                action(
                        "publish-after-research",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"titleConstraint\":{\"operator\":\"CONTAINS\",\"value\":\"Single Research\"},\"evidence\":\"U1\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\"}")));
        RecommendationReActLoop loop = loop(model, new BoardGameRecommendationTools(catalog, research));

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "先核对 Single Research 和一次玩家反馈，然后直接推荐这一款；不要重复调研。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(517));
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().webResearchCalls()).isOne();
        assertThat(researchCalls).hasValue(1);
        assertThat(response.harness().actions())
                .containsSubsequence(
                        "RESOLVE_BGG_REFERENCE",
                        "RESEARCH_GAME_FIT",
                        "SEARCH_BGG_CATALOG",
                        "RECOMMEND_GAMES");
        assertThat(response.researchSources()).isEmpty();
        loop.stopBoundedCalls();
    }

    @Test
    void doesNotConsumeTheBrowseOrPreferenceWhenALaterBrowseArgumentIsInvalid() {
        String preference = "[{\"field\":\"durationMinutes\","
                + "\"value\":{\"minimum\":null,\"maximum\":60},"
                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]";
        ScriptedModel model = new ScriptedModel(
                List.of(
                        action(
                                "invalid-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"offset\":201,\"preferenceUpdates\":"
                                        + preference + "}"),
                        action(
                                "repaired-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"requestedCount\":1,\"requestedCountBasis\":\"U1\",\"offset\":0,\"preferenceUpdates\":"
                                        + preference + "}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                505,
                "One Hour Welcome",
                "一小时欢迎局",
                "A forty-five minute game for a new group."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟，我们四个人第一次同桌；请直接挑一款能完整玩完的。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:CATALOG_OFFSET_INVALID", "UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG");
        assertThat(response.harness().actions().stream()
                        .filter("UPDATE_PREFERENCES"::equals)
                        .count())
                .isEqualTo(1);

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnExplicitCardCountWithoutItsCurrentTurnBasisBeforeMutatingOrReading() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "count-without-evidence",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":2,\"requestedCount\":2,\"preferenceUpdates\":[{"
                                + "\"field\":\"durationMinutes\",\"value\":{\"maximum\":60},"
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "finish-after-rejection",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我还不能把未经归属的数量当成你的明确要求。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                509,
                "Atomic Count",
                "原子数量",
                "A fixture that must not be read after invalid count provenance."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "请给我两款，最多六十分钟。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REQUESTED_COUNT_BASIS_REQUIRED", "REPLY_TO_USER");
        loop.stopBoundedCalls();
    }

    @Test
    void ignoresAnUnsupportedContextualFieldWithoutDiscardingDirectSiblings() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "unsupported-context",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"requestedCount\":3,\"requestedCountBasis\":\"PRODUCT_DEFAULT\",\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U1\",\"evidenceClassification\":\"INFERRED_GROUP_MEMBER_COUNT\"}]}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"你没有说明合作或对抗偏好，我不会把它悄悄变成筛选条件。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(506, "Open Choice", "开放选择", "A flexible game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟；合作还是对抗都还没讨论，先别替我们决定。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(response.profile().interaction())
                .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains(
                        "UPDATE_PREFERENCES",
                        "IGNORED_INVALID_PREFERENCE_UPDATE:PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID",
                        "SEARCH_BGG_CATALOG")
                .doesNotContain("RECORD_CONTEXTUAL_PREFERENCE");

        loop.stopBoundedCalls();
    }

    @Test
    void doesNotCommitTerminalPreferencesWhenReferencedGameOwnershipIsInvalid() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-reference",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"先把时长记下来。\",\"referencedBggIds\":[999],\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我没有已验证的游戏可引用，所以先只确认你的问题，不会伪造游戏依据。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(508, "Unseen Game", "未见之局", "Not read in this turn."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟；先告诉我你是否已经有可引用的游戏资料。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REPLY_ID_NOT_VERIFIED", "REPLY_TO_USER")
                .doesNotContain("UPDATE_PREFERENCES");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnExcludedDirectTargetWithoutPublishingOrPoisoningATypedRecovery() {
        Game excluded = game(520, "Ember Court", "余烬宫廷", "A previously rejected court game.");
        Game selected = game(521, "Northbound", "一路向北", "A travel game the player selected instead.");
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "excluded-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Ember Court\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"先回到《余烬宫廷》；这款已经有完整目录资料。\"}"),
                action(
                        "replacement-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Northbound\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"好，改成你明确选的《一路向北》；它没有被排除，可以作为这轮选择。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(excluded, selected);
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "不要再给我 Ember Court；这次请直接找 Northbound，并把它作为我的选择。",
                        List.of(520),
                        List.of(),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(521));
        assertThat(response.candidatesEvaluated()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:FINAL_ID_EXCLUDED", "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES")
                .doesNotContain("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(response.harness().actions().stream()
                        .filter("RESOLVE_BGG_REFERENCE"::equals)
                        .count())
                .isEqualTo(1);
        assertThat(catalog.localReferenceResolutions).hasValue(2);
        assertThat(catalog.remoteReferenceResolutions).hasValue(2);

        loop.stopBoundedCalls();
    }

    @Test
    void resolverFailuresDoNotConsumeTheStructuredReferenceAttemptBudget() {
        Game selected = game(522, "Northbound", "一路向北", "A travel game with a stable catalog identity.");
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "failed-reference-one",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Fog Atlas\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"我先核对 Fog Atlas，需要目录确认。\"}"),
                action(
                        "failed-reference-two",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Copper Vale\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"我再核对 Copper Vale，仍需目录确认。\"}"),
                action(
                        "recovered-reference",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Northbound\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"目录恢复了，就是《一路向北》；这次返回了完整的结构化游戏身份，可以作为这轮明确选择。\"}")));
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        AtomicInteger unsettledCalls = new AtomicInteger();
        when(tools.resolveLocalReferenceTitle(anyString())).thenAnswer(ignored -> {
            if (unsettledCalls.incrementAndGet() <= 2) {
                throw new IllegalStateException("resolver ended without a structured outcome");
            }
            return new ReferenceObservation(ToolStatus.PARTIAL, List.of(), "REFERENCE_NOT_FOUND");
        });
        when(tools.resolveReferenceTitle("Northbound"))
                .thenReturn(new ReferenceObservation(ToolStatus.SUCCESS, List.of(selected), ""));
        RecommendationReActLoop loop = loop(model, tools);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我提到过 Fog Atlas 和 Copper Vale，但最后明确选 Northbound；请核对后继续。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(522));
        assertThat(response.harness().actions().stream()
                        .filter("REJECTED_ACTION:ACTION_UNAVAILABLE"::equals)
                        .count())
                .isEqualTo(2);
        assertThat(response.harness().actions())
                .contains("RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES")
                .doesNotContain("REJECTED_UNAVAILABLE_ACTION");
        verify(tools, times(3)).resolveLocalReferenceTitle(anyString());
        verify(tools, times(1)).resolveReferenceTitle("Northbound");

        loop.stopBoundedCalls();
    }

    @Test
    void checkpointsATerminalReadBeforeReturningAndReusesItsVerifiedIdentityOnRetry() {
        String userMessage = "我们最后选了静港（Quiet Harbor），请把它作为当前选择。";
        String targetAction = "{\"title\":\"Quiet Harbor\",\"purpose\":\"TARGET_GAME\","
                + "\"evidence\":\"U1\",\"playerReply\":\"好，就是《静港》；它是你这轮明确选定的游戏。\"}";
        Game selected = game(509, "Quiet Harbor", "静港", "A calm game about building a harbor together.");
        RecordingCatalog catalog = new RecordingCatalog(selected);
        AtomicReference<TurnCheckpoint> checkpoint = new AtomicReference<>();
        List<String> publicationOrder = new ArrayList<>();
        RecommendationReActLoop firstLoop = loop(
                new ScriptedModel(List.of(action(
                        "first-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);
        ConversationRequest firstRequest = validatedRequest(userMessage, List.of());

        var firstResponse = firstLoop.converseValidated(
                firstRequest,
                "zh-CN",
                "player",
                ignored -> {},
                value -> {
                    publicationOrder.add("checkpoint");
                    checkpoint.set(value);
                });

        assertThat(firstResponse.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(publicationOrder).containsExactly("checkpoint");
        assertThat(checkpoint.get()).isNotNull();
        assertThat(checkpoint.get().verifiedGames()).singleElement()
                .satisfies(game -> assertThat(game.ranking().bggId()).isEqualTo(509));
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);
        firstLoop.stopBoundedCalls();

        RecommendationReActLoop retryLoop = loop(
                new ScriptedModel(List.of(action(
                        "retry-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);
        ConversationRequest retryRequest = validatedRequest(userMessage, checkpoint.get().verifiedGames());
        var response = retryLoop.converseValidated(
                retryRequest,
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(509));
        assertThat(response.harness().actions()).contains("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);
        retryLoop.stopBoundedCalls();
    }

    @Test
    void resolvesAuthoritativelyWhenAStoredTitleIdentityIsAmbiguous() {
        String userMessage = "我选的是 Quiet Harbor，请按这款继续，不要凭重名记录猜。";
        String targetAction = "{\"title\":\"Quiet Harbor\",\"purpose\":\"TARGET_GAME\","
                + "\"evidence\":\"U1\",\"playerReply\":\"我会先核对同名游戏，再继续这款；目录解析确认了你这次明确指定的游戏身份。\"}";
        Game selected = game(511, "Quiet Harbor", "静港", "The authoritative catalog result.");
        Game sameTitle = game(512, "Quiet Harbor", "静港旧版", "A different stored identity with the same title.");
        RecordingCatalog catalog = new RecordingCatalog(selected);
        RecommendationReActLoop loop = loop(
                new ScriptedModel(List.of(action(
                        "ambiguous-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);

        var response = loop.converseValidated(
                validatedRequest(userMessage, List.of(selected, sameTitle)),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(511));
        assertThat(response.harness().actions())
                .contains("RESOLVE_BGG_REFERENCE")
                .doesNotContain("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);

        loop.stopBoundedCalls();
    }

    @Test
    void canonicalActionIdentityIgnoresObjectFormattingButPreservesArrayAndInvalidPayloadIdentity() {
        RecommendationReActLoop loop = loop(
                new ScriptedModel(List.of()),
                new RecordingCatalog(game(510, "Ordered Evidence", "有序证据", "An identity fixture.")));

        String canonical = loop.actionFingerprint(new ToolCall(
                "one",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"limit\":3,\"types\":[\"PARTY\",\"STRATEGY\"]}"));
        String reordered = loop.actionFingerprint(new ToolCall(
                "two",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{  \"types\" : [\"PARTY\", \"STRATEGY\"], \"limit\" : 3 }"));
        String differentArrayOrder = loop.actionFingerprint(new ToolCall(
                "three",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"limit\":3,\"types\":[\"STRATEGY\",\"PARTY\"]}"));
        String invalid = loop.actionFingerprint(new ToolCall(
                "four", BoardGameRecommendationAgent.BROWSE_TOOL, "{\"limit\":"));
        String sameInvalid = loop.actionFingerprint(new ToolCall(
                "five", BoardGameRecommendationAgent.BROWSE_TOOL, "{\"limit\":"));
        String differentlyFormattedInvalid = loop.actionFingerprint(new ToolCall(
                "six", BoardGameRecommendationAgent.BROWSE_TOOL, "{ \"limit\":"));

        assertThat(reordered).isEqualTo(canonical);
        assertThat(differentArrayOrder).isNotEqualTo(canonical);
        assertThat(sameInvalid).isEqualTo(invalid);
        assertThat(differentlyFormattedInvalid).isNotEqualTo(invalid);

        loop.stopBoundedCalls();
    }

    private static ConversationRequest validatedRequest(String message, List<Game> priorVerifiedGames) {
        return new ConversationRequest(
                RecommendationProfile.empty(),
                message,
                List.of(),
                List.of(new DialogueMessage("user", message)),
                null,
                List.of(),
                List.of(),
                priorVerifiedGames);
    }

    private RecommendationReActLoop loop(BoardGameRecommendationModel model, RecordingCatalog catalog) {
        return loop(model, catalog, ObservationRegistry.NOOP);
    }

    private RecommendationReActLoop loop(
            BoardGameRecommendationModel model,
            RecordingCatalog catalog,
            ObservationRegistry observations) {
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }
        };
        return loop(model, new BoardGameRecommendationTools(catalog, research), observations);
    }

    private RecommendationReActLoop loop(BoardGameRecommendationModel model, BoardGameRecommendationTools tools) {
        return loop(model, tools, ObservationRegistry.NOOP);
    }

    private RecommendationReActLoop loop(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            ObservationRegistry observations) {
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        return new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper(),
                observations);
    }

    private record RecordedObservation(String name, String contextualName, String outcome) {}

    private record RecordedOperation(String stage, String action, String outcome, String recovered) {}

    private static Turn action(String id, String name, String arguments) {
        return new Turn("", List.of(new ToolCall(id, name, arguments)), CompletionStatus.COMPLETE);
    }

    private static BoardGameRecommendationWebResearch configuredResearchThatMustNotRun() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("the optional research action should not execute in this scenario");
            }
        };
    }

    private static BoardGameRecommendationWebResearch noResearch() {
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

    private static Game game(int bggId, String name, String chineseName, String description) {
        return new Game(
                new Ranking(
                        bggId,
                        name,
                        2024,
                        bggId,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        1_500,
                        List.of(BggGameType.PARTY)),
                new Details(
                        name,
                        chineseName,
                        "",
                        2,
                        4,
                        45,
                        new BigDecimal("1.6"),
                        List.of("Party Game"),
                        List.of("Simultaneous Action Selection"),
                        30,
                        45,
                        10,
                        10,
                        "4",
                        "3-4",
                        2,
                        100,
                        List.of(),
                        List.of("Avery Stone"),
                        List.of("Open Shelf"),
                        description,
                        ""));
    }

    private static final class ScriptedModel implements BoardGameRecommendationModel {
        private final ArrayDeque<Turn> actionTurns;
        private final List<Request> requests = new ArrayList<>();

        private ScriptedModel(List<Turn> actionTurns) {
            this.actionTurns = new ArrayDeque<>(actionTurns);
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
            if (actionTurns.isEmpty()) throw new AssertionError("scripted action model exhausted");
            return actionTurns.removeFirst();
        }

        @Override
        public Turn next(Request request, String ownerUsername) {
            return next(request);
        }

    }

    private static final class RecordingCatalog implements BoardGameRecommendationCatalog {
        private final List<Game> games;
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger localReferenceResolutions = new AtomicInteger();
        private final AtomicInteger remoteReferenceResolutions = new AtomicInteger();
        private Runnable beforeSearch = () -> {};

        private RecordingCatalog(Game... games) {
            this.games = List.of(games);
        }

        @Override
        public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
        }

        @Override
        public CandidateSet searchGames(CatalogFilters filters) {
            beforeSearch.run();
            searches.incrementAndGet();
            return new CandidateSet(games.size(), games);
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            return games.stream().filter(game -> bggIds.contains(game.ranking().bggId())).toList();
        }

        @Override
        public List<Game> resolveLocalReferenceTitle(String title) {
            localReferenceResolutions.incrementAndGet();
            return List.of();
        }

        @Override
        public List<Game> resolveReferenceTitle(String title) {
            remoteReferenceResolutions.incrementAndGet();
            String expected = title.strip().replaceAll("\\s+", " ");
            return games.stream()
                    .filter(game -> java.util.stream.Stream.of(
                                    game.ranking().sourceName(),
                                    game.details() == null ? null : game.details().name(),
                                    game.details() == null ? null : game.details().officialChineseName())
                            .filter(java.util.Objects::nonNull)
                            .map(value -> value.strip().replaceAll("\\s+", " "))
                            .anyMatch(value -> value.equalsIgnoreCase(expected)))
                    .toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }
    }
}
