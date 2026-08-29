package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicSubjectKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RecommendationNaturalFrontDoorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesAnExplicitLocalizedTargetBeforeTheSameAgentAuthorsItsCardReply() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        stubStreamingThroughNext(model);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        List<Request> captured = new ArrayList<>();
        List<String> answerParts = new ArrayList<>();
        Game target = game(801, "River Market", "河市集", List.of("Avery Stone"));
        String playerReply = "找到了，就是你指定的《河市集》，对应目录中的 River Market。它的条目身份和基础人数资料已经核对；下面卡片只陈述这款游戏自己的事实，不会借用同名作品或其他候选的信息，你可以据此确认这就是今晚要玩的那一款。";

        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            if (captured.size() == 1) {
                return new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-resolve-target",
                                BoardGameRecommendationAgent.RESOLVE_TOOL,
                                "{\"title\":\"河市集\",\"alternateTitles\":[\"River Market\"],\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\"}")),
                        CompletionStatus.COMPLETE);
            }
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-recommend-target",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            "{\"playerReply\":\""
                                    + playerReply
                                    + "\",\"playerReplyEvidenceIds\":[\"B801:playerCount\"],\"selections\":[{\"bggId\":801,\"why\":{\"text\":\"这是你点名并已核对到的游戏条目。\",\"internalEvidenceIds\":[\"B801:playerCount\"]}}]}")),
                    CompletionStatus.COMPLETE);
        });
        when(tools.resolveLocalReferenceTitle("河市集"))
                .thenReturn(new ReferenceObservation(ToolStatus.PARTIAL, List.of(), "REFERENCE_NOT_FOUND"));
        when(tools.resolveLocalReferenceTitle("River Market"))
                .thenReturn(new ReferenceObservation(ToolStatus.SUCCESS, List.of(target), ""));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚就玩《河市集（River Market）》，只需要找到并确认对应卡片。"),
                "zh-CN",
                "player",
                ignored -> {},
                answerParts::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(playerReply);
        assertThat(answerParts).isEmpty();
        assertThat(response.games())
                .singleElement()
                .satisfies(game -> {
                    assertThat(game.game().ranking().bggId()).isEqualTo(801);
                    assertThat(game.replyParts()).singleElement().satisfies(part -> {
                        assertThat(part.role()).isEqualTo(ReplyPartRole.WHY_FIT);
                        assertThat(part.claim().text()).isEqualTo("这是你点名并已核对到的游戏条目。");
                    });
                });
        assertThat(response.harness().catalogCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsExactly(
                        "RESOLVE_BGG_REFERENCE",
                        "PREPARE_RECOMMENDATION",
                        "MODEL_AUTHORED_RECOMMENDATION",
                        "RECOMMEND_GAMES");
        assertThat(captured.getFirst().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow())
                .satisfies(tool -> {
                    assertThat(tool.description())
                            .contains("then recommend_games writes the reply after seeing its facts");
                    assertThat(tool.inputSchema())
                            .contains(
                                    "alternateTitles",
                                    "\"required\":[\"title\",\"purpose\",\"evidence\"]")
                            .doesNotContain("playerReply")
                            .doesNotContain("\"reason\"");
                });
        assertThat(captured.get(1).messages().getLast().content())
                .contains("River Market", "B801:playerCount", "pendingRecommendation");
        assertThat(captured.get(1).tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .inputSchema())
                .contains("\"enum\":[801]", "B801:playerCount");

        loop.stopBoundedCalls();
    }

    @Test
    void recordsAnExplicitPreferenceThenAnswersNaturallyWithoutRetrieval() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        List<Request> captured = new ArrayList<>();

        when(tools.webResearchConfigured()).thenReturn(true);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            if (captured.size() == 1) {
                return new Turn(
                        "",
                        List.of(new ToolCall(
                                "remember-player-count",
                                BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL,
                                "{\"preferenceUpdates\":{\"evidence\":\"U1\",\"playerCount\":4}}")),
                        CompletionStatus.COMPLETE);
            }
            return new Turn("记住了：以后默认按四个人玩；这次先不推荐。", List.of(), CompletionStatus.COMPLETE);
        });
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                selector,
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "以后默认按四个人玩，先记住就好，不用推荐。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo("记住了：以后默认按四个人玩；这次先不推荐。");
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.profile().playerCount().minimum()).isEqualTo(4);
        assertThat(response.profile().playerCount().maximum()).isEqualTo(4);
        assertThat(captured.getFirst().toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.AUTO);
        assertThat(captured.getFirst().messages().getFirst().content())
                .contains("machine-owned state change", "preferenceUpdates");
        assertThat(captured.get(1).messages().getLast().content()).contains("PREFERENCES_UPDATED");
        assertThat(captured.get(1).tools())
                .anyMatch(tool -> BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL.equals(tool.name()));

        loop.stopBoundedCalls();
    }

    @Test
    void exposesAProtocolFailureCodeWithoutRetryingOrChangingThePlayerFacingBoundary() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenThrow(
                new BoardGameRecommendationModel.ProtocolFailure(
                        "ACTION_PROTOCOL_INVALID",
                        new IllegalStateException("raw provider output stays private")));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                mock(BoardGameRecommendationSelector.class),
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们继续随便聊聊。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage())
                .doesNotContain("ACTION_PROTOCOL_INVALID", "raw provider output");
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly(
                "MODEL_PROTOCOL_FAILED:ACTION_PROTOCOL_INVALID",
                "UNAVAILABLE:MODEL_PROTOCOL_FAILED:ACTION_PROTOCOL_INVALID");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesANaturalAnswerWithoutInventingAReplyActionOrRetry() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        stubStreamingThroughNext(model);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        List<Request> captured = new ArrayList<>();
        List<String> answerParts = new ArrayList<>();
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return new Turn("你好！我可以帮你找游戏、比较候选，也可以继续聊桌游。", List.of(), CompletionStatus.COMPLETE);
        });
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                mock(BoardGameRecommendationSelector.class),
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "你好"),
                "zh-CN",
                "player",
                ignored -> {},
                answerParts::add);

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo("你好！我可以帮你找游戏、比较候选，也可以继续聊桌游。");
        assertThat(response.harness().actions()).containsExactly("FINAL_ANSWER");
        assertThat(answerParts).containsExactly("你好！我可以帮你找游戏、比较候选，也可以继续聊桌游。");
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().tools())
                .noneMatch(tool -> "reply_to_user".equals(tool.name()));

        loop.stopBoundedCalls();
    }

    @Test
    void failsAnEmptyModelTurnWithoutRetryingOrFabricatingAReply() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn("", List.of(), CompletionStatus.COMPLETE));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                mock(BoardGameRecommendationSelector.class),
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "请推荐一款游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(response.assistantMessage()).doesNotContain("typed", "JSON");
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly(
                "EMPTY_MODEL_RESPONSE",
                "UNAVAILABLE:EMPTY_MODEL_RESPONSE");

        loop.stopBoundedCalls();
    }

    @Test
    void asksOneUsefulQuestionBeforeAnyCatalogReadWhenThePlayerInvitesGuidance() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        String question = "我可以先给你一个有差异的起点：今晚更想一起琢磨，还是互相算计？";

        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "ask-one",
                            BoardGameRecommendationAgent.ASK_TOOL,
                            "{\"question\":\""
                                    + question
                                    + "\",\"options\":[\"一起琢磨\",\"互相算计\"]}")),
                    CompletionStatus.COMPLETE);
        });
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                selector,
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "周末四个人，但大家都没想好玩什么。你先问我一个真正有用的问题吧。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
        assertThat(response.assistantMessage()).isEqualTo(question);
        assertThat(response.clarification().options())
                .extracting(BoardGameRecommendationAgent.ClarificationOption::label)
                .containsExactly("一起琢磨", "互相算计");
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("ASK_USER");
        assertThat(captured.get().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(BoardGameRecommendationAgent.ASK_TOOL);

        loop.stopBoundedCalls();
    }

    @Test
    void usesAgentChosenCatalogPagesToReturnANewSlateWithoutRepeatingCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        List<Integer> observedOffsets = new ArrayList<>();
        List<Game> catalogGames = List.of(
                game(901, "First Horizon", "Range Designer"),
                game(902, "Second Horizon", "Range Designer"),
                game(903, "Third Horizon", "Range Designer"),
                game(904, "Fourth Horizon", "Range Designer"));
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(catalogGames.size(), catalogGames.stream().limit(maximum).toList());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return catalogGames.stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                observedOffsets.add(filters.offset());
                return new CandidateSet(
                        catalogGames.size(),
                        catalogGames.stream()
                                .skip(filters.offset())
                                .limit(filters.maximum())
                                .toList());
            }

            @Override
            public int gameCount() {
                return catalogGames.size();
            }
        };
        when(research.configured()).thenReturn(false);
        when(model.configured("player")).thenReturn(true);
        respondWithReadsThenRecommendation(model, List.of(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "browse-first-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":2,\"requestedCount\":2,\"requestedCountBasis\":\"U1\",\"offset\":0}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "browse-second-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":2,\"requestedCount\":2,\"requestedCountBasis\":\"U1\",\"offset\":2}")),
                        CompletionStatus.COMPLETE)));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var first = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "给我两款有远方感的桌游。"),
                "zh-CN",
                "player",
                ignored -> {});
        List<Integer> firstIds = first.games().stream()
                .map(entry -> entry.game().ranking().bggId())
                .toList();
        var second = loop.converse(
                new ConversationRequest(
                        first.profile(),
                        "这两款都不要，同样方向再来两款。",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        firstIds),
                "zh-CN",
                "player",
                ignored -> {});
        List<Integer> secondIds = second.games().stream()
                .map(entry -> entry.game().ranking().bggId())
                .toList();

        assertThat(observedOffsets).containsExactly(0, 2);
        assertThat(firstIds).containsExactly(901, 902);
        assertThat(secondIds).containsExactly(903, 904).doesNotContainAnyElementsOf(firstIds);
        assertModelAuthoredPublication(first);
        assertModelAuthoredPublication(second);
        assertThat(first.games()).allSatisfy(this::assertEvidenceBackedReplyParts);
        assertThat(second.games()).allSatisfy(this::assertEvidenceBackedReplyParts);
        assertThat(second.harness().actions())
                .containsSubsequence("SEARCH_BGG_CATALOG", "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void plansFromPublicAliasDiscoveryIntoTheVerifiedBggDesignerRelation() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        AtomicReference<String> designerQuery = new AtomicReference<>();
        Game first = game(701, "Anchor Workshop", "Studio Architect");
        Game second = game(702, "Second Workshop", "Studio Architect");
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(2, List.of(first, second));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return bggIds.stream()
                        .map(id -> id == 701 ? first : id == 702 ? second : null)
                        .filter(java.util.Objects::nonNull)
                        .toList();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return names.stream()
                        .filter("Anchor Workshop"::equals)
                        .map(ignored -> first.ranking())
                        .toList();
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                if (!filters.designers().isEmpty()) designerQuery.set(filters.designers().getFirst());
                if (!filters.designers().isEmpty()
                        && !"Studio Architect".equals(filters.designers().getFirst())) {
                    return new CandidateSet(2, List.of());
                }
                return new CandidateSet(2, List.of(first, second).stream()
                        .limit(filters.maximum())
                        .toList());
            }

            @Override
            public int gameCount() {
                return 2;
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Anchor Workshop",
                                "A sourced article identifies this as the creator's work.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Tabletop creator profile",
                                "https://tabletop.example.test/creator",
                                "tabletop.example.test"))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        respondWithReadsThenRecommendation(model, List.of(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-local-wrong-identity",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"designers\":[\"Wrong Architect\"],\"purpose\":\"IDENTITY_ONLY\",\"requestedCount\":2,\"requestedCountBasis\":\"U1\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Studio Architect alias\",\"goal\":\"SELECTABLE_CARDS\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-filter",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"designers\":[\"Studio Architect\"],\"limit\":2,\"requestedCount\":2,\"requestedCountBasis\":\"U1\"}")),
                        CompletionStatus.COMPLETE)));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "你知道桌游圈里的‘齿轮先生’吗？请先说出他是谁，再推荐两款他的游戏。"
                                + "请把两款可选卡片都给我。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(designerQuery).hasValue("Studio Architect");
        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(701, 702);
        assertModelAuthoredPublication(response);
        assertThat(response.games()).allSatisfy(this::assertEvidenceBackedReplyParts);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains(
                        "SEARCH_BGG_CATALOG",
                        "DISCOVER_CANDIDATES",
                        "DISCOVERY_CANDIDATE_LEADS_RECORDED",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesSourceBackedFictionalFranchiseTitlesWhenDiscoveryNeedsSelectableCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        AtomicReference<DiscoveryRequest> capturedDiscovery = new AtomicReference<>();
        Game first = game(711, "Orion Frontier", "Morgan Vale");
        Game second = game(712, "Orion Rebellion", "Riley North");
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(2, List.of(first, second));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return List.of(first, second).stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return List.of(first, second).stream()
                        .filter(game -> names.contains(game.ranking().sourceName()))
                        .map(Game::ranking)
                        .toList();
            }

            @Override
            public int gameCount() {
                return 2;
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                capturedDiscovery.set(request);
                if (request.goal() != DiscoveryGoal.SELECTABLE_CARDS) return Optional.empty();
                return Optional.of(new CandidateDiscovery(
                        List.of(
                                new CandidateLead(
                                        "Orion Frontier",
                                        "The franchise index lists this tabletop title.",
                                        List.of(1)),
                                new CandidateLead(
                                        "Orion Rebellion",
                                        "The franchise index lists this tabletop title.",
                                        List.of(1))),
                        List.of(new Source(
                                1,
                                "Orion Saga tabletop index",
                                "https://tabletop.example.test/orion-saga",
                                "tabletop.example.test"))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        respondWithReadsThenRecommendation(model, List.of(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-franchise",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Orion Saga IP\",\"goal\":\"SELECTABLE_CARDS\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-verify-franchise-cards",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"textQuery\":\"Orion Saga\",\"sort\":\"RELEVANCE\",\"requestedCount\":2,\"requestedCountBasis\":\"U1\"}")),
                        CompletionStatus.COMPLETE)));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "‘Orion Saga’这个虚构科幻 IP 有哪些桌游？请给我两款卡片。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(capturedDiscovery.get()).satisfies(request -> {
            assertThat(request.subject()).isEqualTo("Orion Saga IP");
            assertThat(request.goal()).isEqualTo(DiscoveryGoal.SELECTABLE_CARDS);
        });
        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(711, 712);
        assertModelAuthoredPublication(response);
        assertThat(response.games()).allSatisfy(this::assertEvidenceBackedReplyParts);
        assertThat(response.researchSources()).isEmpty();
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains(
                        "DISCOVER_CANDIDATES",
                        "DISCOVERY_CANDIDATE_LEADS_RECORDED",
                        "SEARCH_BGG_CATALOG",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void answersASourceBackedEventRelationshipWithoutForcingBggCanonicalization() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("public context must stay within the single discovery read");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(),
                        List.of(new Source(
                                1,
                                "Convention organizer",
                                "https://events.example.test/north-harbor",
                                "events.example.test")),
                        List.of(new PublicContextEvidence(
                                "P1",
                                PublicSubjectKind.EVENT,
                                "North Harbor Games Week",
                                "organized by",
                                "Harbor Tabletop Association",
                                "North Harbor Games Week is organized by the Harbor Tabletop Association.",
                                List.of(1)))));
            }
        };
        AtomicReference<Request> completionRequest = new AtomicReference<>();
        String reply = "North Harbor Games Week 的主办方是 Harbor Tabletop Association。";
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player")))
                .thenReturn(new Turn(
                        "",
                        List.of(new ToolCall(
                                "discover-event",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"North Harbor Games Week\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    completionRequest.set(invocation.getArgument(0));
                    return new Turn(reply, List.of(), CompletionStatus.COMPLETE);
                });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今年的 North Harbor Games Week 是谁主办的？只回答这个公开关系。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(reply);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsSubsequence(
                "DISCOVERY_PUBLIC_CONTEXT_VERIFIED",
                "DISCOVER_CANDIDATES",
                "FINAL_ANSWER");
        assertThat(response.researchSources()).singleElement().satisfies(source ->
                assertThat(source.url()).isEqualTo("https://events.example.test/north-harbor"));
        assertThat(completionRequest.get()).satisfies(request -> {
            assertThat(request.messages()).anySatisfy(message ->
                    assertThat(message.content()).contains("P1", "Harbor Tabletop Association"));
            assertThat(request.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.AUTO);
            assertThat(request.tools()).noneMatch(tool -> "finish_identity_check".equals(tool.name()));
        });
        verify(catalog, never()).searchGames(any());
        verify(catalog, never()).searchByNames(any());
        verify(catalog, never()).findGamesByIds(any());

        loop.stopBoundedCalls();
    }

    @Test
    void publishesOnlySourcesReturnedByTheCurrentDiscovery() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("public context must stay within the single discovery read");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(),
                        List.of(new Source(
                                1,
                                "Public organization record",
                                "https://records.example.test/copper-city",
                                "records.example.test")),
                        List.of(new PublicContextEvidence(
                                "P1",
                                PublicSubjectKind.ORGANIZATION,
                                "Copper City Tabletop Council",
                                "operates",
                                "Copper City Games Forum",
                                "The Copper City Tabletop Council operates the Copper City Games Forum.",
                                List.of(1)))));
            }
        };
        String reply = "公开资料显示，Copper City Games Forum 由 Copper City Tabletop Council 运营。";
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "discover-organization",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Copper City Games Forum\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(reply, List.of(), CompletionStatus.COMPLETE));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "Copper City Games Forum 是由哪个组织运营的？"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(reply);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).contains("FINAL_ANSWER").noneMatch(action -> action.startsWith("REJECTED_"));
        assertThat(response.researchSources()).singleElement().satisfies(source ->
                assertThat(source.url()).isEqualTo("https://records.example.test/copper-city"));
        verify(catalog, never()).searchGames(any());
        verify(catalog, never()).searchByNames(any());
        verify(catalog, never()).findGamesByIds(any());

        loop.stopBoundedCalls();
    }

    @Test
    void keepsIdentityOnlyDiscoveryOutOfCardsAndPublishesTheModelAuthoredUnresolvedReply() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        AtomicReference<DiscoveryRequest> capturedDiscovery = new AtomicReference<>();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("an identity-only turn must not start fit research");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                capturedDiscovery.set(request);
                return Optional.empty();
            }
        };
        String unresolvedReply = "我查过公开资料，但没有找到足够证据确认这个称呼指的是谁。"
                + "为了不凭记忆乱猜，我先停在这里；你可以补充更精确的名称或一条来源。";
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-identity",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Silver Comet alias\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(unresolvedReply, List.of(), CompletionStatus.COMPLETE));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "不要推荐卡片，我只想确认桌游圈里的‘Silver Comet’这个称呼是谁。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(capturedDiscovery.get()).satisfies(request -> {
            assertThat(request.subject()).isEqualTo("Silver Comet alias");
            assertThat(request.goal()).isEqualTo(DiscoveryGoal.IDENTITY_ONLY);
        });
        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(unresolvedReply);
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().actions())
                .contains(
                        "DISCOVER_CANDIDATES",
                        "FINAL_ANSWER")
                .doesNotContain("RECOMMEND_GAMES", "RESEARCH_GAME_FIT");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesTheModelAuthoredFailureExplanationWhenPublicResearchIsUnavailable() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        AtomicReference<Request> followupRequest = new AtomicReference<>();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("an identity-only turn must not start fit research");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                throw new BoardGameRecommendationWebResearch.WebResearchUnavailableException(
                        "PROVIDER_HTTP_ERROR");
            }
        };
        String unavailableReply = "这次公开资料查询没有完成，所以我还不能可靠确认主办方。"
                + "我不会根据记忆补上答案；你可以稍后重试。";
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player")))
                .thenReturn(new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-unavailable",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"current event organizer\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    followupRequest.set(invocation.getArgument(0));
                    return new Turn(unavailableReply, List.of(), CompletionStatus.COMPLETE);
                });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "不要推荐卡片，只确认这个活动现在由谁主办。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage())
                .isEqualTo(unavailableReply)
                .doesNotContain("PROVIDER_HTTP_ERROR");
        assertThat(response.games()).isEmpty();
        assertThat(response.harness().actions())
                .contains(
                        "WEB_RESEARCH_DEGRADED:PROVIDER_HTTP_ERROR",
                        "FINAL_ANSWER")
                .doesNotContain("RECOMMEND_GAMES");
        assertThat(followupRequest.get().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        BoardGameRecommendationAgent.BROWSE_TOOL)
                .doesNotContain(
                        BoardGameRecommendationAgent.LOOKUP_TOOL,
                        BoardGameRecommendationAgent.RESEARCH_TOOL);
        loop.stopBoundedCalls();
    }

    @Test
    void appliesExplicitHardPreferencesOnTheCandidateReadBeforeSelectingCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        AtomicReference<Request> actionRequest = new AtomicReference<>();
        Game eligible = game(801, "Clockwork Supper", "Studio Architect", 75);
        Game tooLong = game(802, "Long Workshop", "Studio Architect", 90);
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(2, List.of(eligible, tooLong));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return List.of(eligible, tooLong).stream()
                        .filter(game -> bggIds.contains(game.ranking().bggId()))
                        .toList();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return List.of();
            }

            @Override
            public CandidateSet searchGames(CatalogFilters filters) {
                return new CandidateSet(2, List.of(eligible, tooLong));
            }

            @Override
            public int gameCount() {
                return 2;
            }
        };
        when(research.configured()).thenReturn(false);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            if (hasRecommendationAction(request)) return terminalRecommendation(request);
            actionRequest.set(request);
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-browse",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            """
                            {"purpose":"SELECTABLE_CARDS","limit":8,"requestedCount":1,"requestedCountBasis":"U1","preferenceUpdates":[
                              {"field":"playerCount","value":4,"evidence":"U1","evidenceClassification":"DIRECT"},
                              {"field":"durationMinutes","value":{"minimum":null,"maximum":75},"evidence":"U1","evidenceClassification":"DIRECT"}
                            ]}
                            """)),
                    CompletionStatus.COMPLETE);
        });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());
        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "我们 4 个人，最多 75 分钟，推荐一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(801);
        assertThat(response.profile().playerCount().minimum()).isEqualTo(4);
        assertThat(response.profile().playerCount().maximum()).isEqualTo(4);
        assertThat(response.profile().durationMinutes().maximum()).isEqualTo(75);
        assertModelAuthoredPublication(response);
        assertThat(response.games()).allSatisfy(this::assertEvidenceBackedReplyParts);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions())
                .containsSubsequence("UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG", "RECOMMEND_GAMES")
                .doesNotContain("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
        Request observedActionRequest = actionRequest.get();
        assertThat(observedActionRequest.tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .doesNotContain("recommend_games");
        String browseSchema = observedActionRequest.tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema();
        assertThat(browseSchema)
                .contains(
                        "preferenceUpdates",
                        "requestedCount",
                        "evidence",
                        "\"playerCount\"")
                .as("the model-facing preference schema exposes one canonical player-count field")
                .doesNotContain("\"players\"");
        assertThat(observedActionRequest.messages().getFirst().content())
                .contains(
                        "Every candidate read must set requestedCount plus requestedCountBasis",
                        "defaultRecommendationCount",
                        "never reuse an older turn's count");

        loop.stopBoundedCalls();
    }

    @Test
    void verifiedIdentityFinishesBeforeAnotherModelTurnCanReplaceItWithTheEvidenceCarrierGame() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        Game evidenceCarrier = game(701, "Anchor Workshop", "Studio Architect");
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(1, List.of(evidenceCarrier));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return bggIds.contains(701) ? List.of(evidenceCarrier) : List.of();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return names.contains("Anchor Workshop") ? List.of(evidenceCarrier.ranking()) : List.of();
            }

            @Override
            public int gameCount() {
                return 1;
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Anchor Workshop",
                                "A sourced profile ties the alias to the game's designer.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Tabletop creator profile",
                                "https://tabletop.example.test/creator",
                                "tabletop.example.test")),
                        List.of(new PublicContextEvidence(
                                "P1",
                                PublicSubjectKind.PERSON,
                                "Studio Architect alias",
                                "refers to",
                                "Studio Architect",
                                "The community alias refers to board-game designer Studio Architect.",
                                List.of(1)))));
            }
        };
        AtomicReference<Request> followupRequest = new AtomicReference<>();
        when(model.configured("player")).thenReturn(true);
        String naturalReply = "知道，你说的是 Studio Architect。这个圈内叫法还挺形象的；如果你愿意，我们可以接着聊聊他的作品有什么共同气质。";
        when(model.next(any(), eq("player")))
                .thenReturn(new Turn(
                        "UNTRUSTED WRONG DRAFT",
                        List.of(new ToolCall(
                                "call-discover",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U2\",\"subject\":\"Studio Architect alias\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    followupRequest.set(invocation.getArgument(0));
                    return new Turn(naturalReply, List.of(), CompletionStatus.COMPLETE);
                });
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "你知道桌游圈里的‘齿轮先生’吗？",
                        List.of(),
                        List.of(
                                new DialogueMessage("user", "上次我们聊到重度欧式。"),
                                new DialogueMessage("assistant", "记得，你更在意机制之间的联动。")),
                        null,
                        List.of(),
                        List.of(),
                        List.of()),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(naturalReply);
        assertThat(response.assistantMessage()).doesNotContain("Anchor Workshop");
        assertThat(response.harness().actions())
                .contains("FINAL_ANSWER")
                .noneMatch(action -> action.startsWith("REJECTED_"));
        Request observedFollowup = followupRequest.get();
        assertThat(observedFollowup.messages().get(1).content())
                .contains("上次我们聊到重度欧式", "记得，你更在意机制之间的联动", "齿轮先生");
        assertThat(observedFollowup.messages().get(2).content()).isEqualTo("UNTRUSTED WRONG DRAFT");
        assertThat(observedFollowup.messages().get(3).content())
                .contains("publicContextEvidence", "Studio Architect", "verifiedGames")
                .doesNotContain("discoveredRelationship", "UNTRUSTED WRONG DRAFT");

        loop.stopBoundedCalls();
    }

    @Test
    void stopsAfterAPlayerNamedGameBoundsAnUnresolvedCreatorAlias() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        Game contextGame = game(
                703,
                "Galactic Voyage",
                "银河漫游",
                List.of("Avery Stone", "Blake North", "Casey Rivers"));
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(1, List.of(contextGame));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return bggIds.contains(703) ? List.of(contextGame) : List.of();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return names.stream()
                        .filter(name -> name.equals("银河漫游") || name.equals("Galactic Voyage"))
                        .map(ignored -> contextGame.ranking())
                        .toList();
            }

            @Override
            public int gameCount() {
                return 1;
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("an identity boundary must not start fit research");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.empty();
            }
        };
        when(model.configured("player")).thenReturn(true);
        String unresolvedReply = "我查过公开资料，但没有找到足够证据确认这个称呼指的是谁。"
                + "我能确认你提到的游戏条目，但它不足以证明这个称呼的身份关系；为了不凭记忆乱猜，我先停在这里。";
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"the creator alias\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-resolve-context",
                                BoardGameRecommendationAgent.RESOLVE_TOOL,
                                "{\"title\":\"银河漫游\",\"purpose\":\"COMPARISON_REFERENCE\",\"evidence\":\"U1\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(unresolvedReply, List.of(), CompletionStatus.COMPLETE));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "不要推荐，我只想确认这个圈内称呼是谁。我记得他参与设计了《银河漫游》。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(unresolvedReply);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .containsSubsequence(
                        "DISCOVER_CANDIDATES",
                        "RESOLVE_BGG_REFERENCE",
                        "FINAL_ANSWER")
                .doesNotContain("RESEARCH_GAME_FIT");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesAGroupAliasOnlyWhenOneBggGameVerifiesEveryDesigner() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        List<String> designers = List.of("Avery Stone", "Blake North", "Casey Rivers");
        Game evidenceCarrier = game(704, "Shared Orbit", "共同轨道", designers);
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                return new CandidateSet(1, List.of(evidenceCarrier));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return bggIds.contains(704) ? List.of(evidenceCarrier) : List.of();
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return names.contains("Shared Orbit") ? List.of(evidenceCarrier.ranking()) : List.of();
            }

            @Override
            public int gameCount() {
                return 1;
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                return Optional.of(new CandidateDiscovery(
                        List.of(new CandidateLead(
                                "Shared Orbit",
                                "A source applies the community name to the full design team.",
                                List.of(1))),
                        List.of(new Source(
                                1,
                                "Design team profile",
                                "https://tabletop.example.test/shared-orbit",
                                "tabletop.example.test")),
                        List.of(new PublicContextEvidence(
                                "P1",
                                PublicSubjectKind.ENTITY,
                                "the team alias",
                                "refers collectively to",
                                String.join(", ", designers),
                                "The community team alias refers collectively to "
                                        + String.join(", ", designers)
                                        + ".",
                                List.of(1)))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        String groupReply = "知道，这个称呼说的不是某一位，而是 Avery Stone、Blake North 和 Casey Rivers 这组设计搭档。你要是感兴趣，我们还可以继续聊他们共同作品里的设计取向。";
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-group",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"the team alias\",\"goal\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(groupReply, List.of(), CompletionStatus.COMPLETE));
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "你知道桌游圈里这个团队称呼吗？"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(groupReply);
        assertThat(response.harness().actions()).contains("FINAL_ANSWER");

        loop.stopBoundedCalls();
    }

    private void assertEvidenceBackedReplyParts(BoardGameRecommendationAgent.RecommendedGame game) {
        assertThat(game.replyParts()).hasSizeBetween(1, 2);
        assertThat(game.replyParts().getFirst().role()).isEqualTo(ReplyPartRole.WHY_FIT);
        assertThat(game.replyParts()).allSatisfy(part -> {
            assertThat(part.claim().type())
                    .isEqualTo(com.rulepilot.recommendation.CandidateClaim.Type.PREFERENCE_INFERENCE);
            assertThat(part.claim().bggId()).isEqualTo(game.game().ranking().bggId());
            assertThat(part.claim().evidence()).isNotEmpty().allSatisfy(evidence ->
                    assertThat(evidence.bggId()).isEqualTo(game.game().ranking().bggId()));
            assertThat(part.claim().text())
                    .hasSizeGreaterThanOrEqualTo(12)
                    .doesNotContain("一条已核对", "选择边界：");
        });
    }

    private void assertModelAuthoredPublication(BoardGameRecommendationAgent.ConversationResponse response) {
        assertThat(response.assistantMessage()).hasSizeGreaterThanOrEqualTo(80);
        assertThat(response.harness().fallbackUsed()).isFalse();
        assertThat(response.harness().actions())
                .contains("MODEL_AUTHORED_RECOMMENDATION", "RECOMMEND_GAMES");
    }

    private void respondWithReadsThenRecommendation(
            BoardGameRecommendationModel model,
            List<Turn> reads) {
        ArrayDeque<Turn> pending = new ArrayDeque<>(reads);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            if (hasRecommendationAction(request)) return terminalRecommendation(request);
            if (pending.isEmpty()) throw new AssertionError("scripted recommendation read exhausted");
            return pending.removeFirst();
        });
    }

    private boolean hasRecommendationAction(Request request) {
        return request.tools().stream()
                .anyMatch(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()));
    }

    private Turn terminalRecommendation(Request request) {
        var tool = request.tools().stream()
                .filter(candidate -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(candidate.name()))
                .findFirst()
                .orElseThrow();
        try {
            JsonNode schema = json.readTree(tool.inputSchema());
            int selectionCount = schema.path("properties")
                    .path("selections")
                    .path("maxItems")
                    .asInt();
            JsonNode choices = schema.path("properties")
                    .path("selections")
                    .path("items")
                    .path("oneOf");
            var arguments = json.createObjectNode();
            arguments.put(
                    "playerReply",
                    "我已经根据这一轮核验后的候选事实完成推荐。下面每张卡片都会说明为什么值得考虑，以及现有资料能支持到什么边界；我不会把未核对的体验当成保证，也不会借用另一款候选的证据来补写当前卡片。你可以先按这些事实判断，再决定是否继续比较。");
            arguments.putArray("playerReplyEvidenceIds").add(schema.path("properties")
                    .path("playerReplyEvidenceIds")
                    .path("items")
                    .path("enum")
                    .path(0)
                    .asText());
            var selections = arguments.putArray("selections");
            for (int index = 0; index < selectionCount; index++) {
                JsonNode choice = choices.path(index);
                int bggId = choice.path("properties").path("bggId").path("enum").path(0).asInt();
                String evidenceId = choice.path("properties")
                        .path("why")
                        .path("properties")
                        .path("internalEvidenceIds")
                        .path("items")
                        .path("enum")
                        .path(0)
                        .asText();
                var selection = selections.addObject();
                selection.put("bggId", bggId);
                var why = selection.putObject("why");
                why.put("text", "这是模型根据当前候选已核验事实写下的完整推荐理由。");
                why.putArray("internalEvidenceIds").add(evidenceId);
            }
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "terminal-recommendation",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            json.writeValueAsString(arguments))),
                    CompletionStatus.COMPLETE);
        } catch (Exception exception) {
            throw new AssertionError("could not build terminal recommendation action", exception);
        }
    }

    private RecommendationAgentState optionalDeadlineState() {
        return new RecommendationAgentState(
                new ConversationRequest(RecommendationProfile.empty(), "exercise an optional capability"),
                System.nanoTime(),
                "player",
                false,
                3);
    }

    private void stubStreamingThroughNext(BoardGameRecommendationModel model) {
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String owner = invocation.getArgument(1);
            Consumer<String> listener = invocation.getArgument(2);
            Turn turn = model.next(request, owner);
            if (turn.toolCalls().isEmpty() && !turn.text().isBlank()) {
                listener.accept(turn.text());
            }
            return turn;
        });
    }

    private Game game(int id, String name, String designer) {
        return game(id, name, designer, 90);
    }

    private Game game(int id, String name, String designer, int maximumMinutes) {
        return game(id, name, "", List.of(designer), maximumMinutes);
    }

    private Game game(int id, String name, String officialChineseName, List<String> designers) {
        return game(id, name, officialChineseName, designers, 90);
    }

    private Game game(
            int id,
            String name,
            String officialChineseName,
            List<String> designers,
            int maximumMinutes) {
        return new Game(
                new Ranking(
                        id,
                        name,
                        2024,
                        id,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        1_000,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        name,
                        officialChineseName,
                        "",
                        1,
                        4,
                        maximumMinutes,
                        new BigDecimal("3.6"),
                        List.of("Strategy"),
                        List.of("Hand Management"),
                        60,
                        maximumMinutes,
                        12,
                        12,
                        "3",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        designers,
                        List.of("Studio Publisher"),
                        "A strategy game with interlocking actions.",
                        ""));
    }
}
