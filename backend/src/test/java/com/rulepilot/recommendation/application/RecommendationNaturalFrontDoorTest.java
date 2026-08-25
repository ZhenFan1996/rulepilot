package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredTurn;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.RelationshipKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.ResolvedRelationship;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class RecommendationNaturalFrontDoorTest {

    @Test
    void resolvesAnExplicitLocalizedTargetAndPublishesItsCardInOneAgentDecision() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        Game target = game(801, "River Market", "河市集", List.of("Avery Stone"));
        String playerReply = "找到了，就是你指定的《河市集》。这张卡对应 River Market，可以直接继续阅读规则书、生成讲解，再进入答疑。";

        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-resolve-target",
                            BoardGameRecommendationAgent.RESOLVE_TOOL,
                            "{\"title\":\"河市集\",\"alternateTitles\":[\"River Market\"],\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\",\"playerReply\":\""
                                    + playerReply
                                    + "\"}")),
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
                        "今晚就玩《河市集（River Market）》，请找到后让我继续读规则书和讲解。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.assistantMessage()).isEqualTo(playerReply);
        assertThat(response.recommendationLead()).isEqualTo(playerReply);
        assertThat(response.games())
                .singleElement()
                .satisfies(game -> {
                    assertThat(game.game().ranking().bggId()).isEqualTo(801);
                    assertThat(game.replyParts()).isEmpty();
                });
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsExactly("RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
        assertThat(captured.get().tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.RESOLVE_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow())
                .satisfies(tool -> {
                    assertThat(tool.description())
                            .contains("same action immediately returns the selectable card");
                    assertThat(tool.inputSchema())
                            .contains("alternateTitles", "playerReply")
                            .doesNotContain("\"reason\"");
                });
        verify(model, never()).streamStructured(any(), eq("player"), any());

        loop.stopBoundedCalls();
    }

    @Test
    void givesFieldSpecificRepairForAnInvalidReferenceTitleThenAcceptsANewAction() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        AtomicReference<Request> repairRequest = new AtomicReference<>();
        Game target = game(802, "Lantern Passage", "灯笼渡口", List.of("Blake North"));

        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player")))
                .thenReturn(new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-invalid-title",
                                BoardGameRecommendationAgent.RESOLVE_TOOL,
                                "{\"title\":\"\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\",\"playerReply\":\"找到了，可以继续看规则。\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    repairRequest.set(invocation.getArgument(0));
                    return new Turn(
                            "",
                            List.of(new ToolCall(
                                    "call-corrected-title",
                                    BoardGameRecommendationAgent.RESOLVE_TOOL,
                                    "{\"title\":\"Lantern Passage\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\",\"playerReply\":\"找到了，就是《灯笼渡口》；它是你明确指定的游戏，可以继续打开规则书。\"}")),
                            CompletionStatus.COMPLETE);
                });
        when(tools.resolveLocalReferenceTitle("Lantern Passage"))
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
                        "请直接找到《Lantern Passage》，然后让我读规则书。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .containsExactly(
                        "REJECTED_ACTION:REFERENCE_TITLE_LENGTH_INVALID",
                        "RESOLVE_BGG_REFERENCE",
                        "RECOMMEND_GAMES");
        assertThat(repairRequest.get().messages().getLast().content())
                .contains(
                        "REFERENCE_TITLE_LENGTH_INVALID",
                        "Copy only the exact board-game title substring",
                        "1 to 160 characters");
        verify(model, never()).streamStructured(any(), eq("player"), any());

        loop.stopBoundedCalls();
    }

    @Test
    void usesTheTypedReplyActionForALightConversationWithoutRetrieval() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        String answer = "可以，我们先不急着套人数和时长。你只要说说最近哪一局让你意犹未尽，我会从那种感觉开始帮你找。";

        when(tools.webResearchConfigured()).thenReturn(true);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            captured.set(request);
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "reply-light-conversation",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            new ObjectMapper().writeValueAsString(java.util.Map.of("playerReply", answer)))),
                    CompletionStatus.COMPLETE);
        });
        RecommendationReActLoop loop = new RecommendationReActLoop(
                model,
                tools,
                selector,
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());
        List<String> streamed = new ArrayList<>();

        var response = loop.converse(
                new ConversationRequest(RecommendationProfile.empty(), "我还没想好要玩什么，我们先随便聊聊？"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_ASSISTED);
        assertThat(response.assistantMessage()).isEqualTo(answer);
        assertThat(streamed).containsExactly(answer);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("REPLY_TO_USER");

        Request first = captured.get();
        assertThat(first).isNotNull();
        assertThat(first.maxOutputTokens())
                .as("an open conversational turn must have enough room to finish naturally")
                .isGreaterThanOrEqualTo(512);
        assertThat(first.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        assertThat(first.tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        BoardGameRecommendationAgent.ASK_TOOL);
        var toolSchemas = first.tools().stream().collect(Collectors.toMap(
                BoardGameRecommendationModel.ToolSpec::name,
                BoardGameRecommendationModel.ToolSpec::inputSchema));
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.SEARCH_TOOL))
                .contains("preferenceUpdates");
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.DISCOVER_TOOL))
                .contains("subject", "afterIdentity", "RECOMMEND_WITH_CARDS")
                .doesNotContain("goalPlan", "workAfterIdentity")
                .doesNotContain("preferenceUpdates");
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.BROWSE_TOOL))
                .contains(
                        "purpose",
                        "categories",
                        "mechanics",
                        "designers",
                        "publishers",
                        "families",
                        "minimumPublicationYear",
                        "minimumAverageRating",
                        "minimumRatingsCount",
                        "textQuery",
                        "RELEVANCE",
                        "offset",
                        "preferenceUpdates");
        assertThat(first.tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .description())
                .contains("local BGG catalog without public web latency");
        String system = first.messages().getFirst().content();
        assertThat(system)
                .contains(
                        "one knowledgeable and natural board-game companion",
                        "Run the ReAct loop yourself",
                        "actions all belong to you, not to separate models or roles",
                        "Prefer the local BGG catalog",
                        "Write the complete player-facing reply freely and naturally")
                .doesNotContain(
                        "Direct prose is for conversation that needs no external information",
                        "shared long-lived cache precedes any cold network search",
                        "reuse the canonical designer")
                .hasSizeLessThan(2_500);
        String userInput = first.messages().getLast().content();
        assertThat(userInput)
                .contains("recentConversation", "随便聊聊")
                .doesNotContain("executionBudget", "availableCapabilities", "\"goal\"");
        verify(model, never()).streamStructured(any(), eq("player"), any());

        loop.stopBoundedCalls();
    }

    @Test
    void recordsAnExplicitPreferenceThroughOneTypedReplyWithoutRetrieval() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        AtomicReference<Request> captured = new AtomicReference<>();

        when(tools.webResearchConfigured()).thenReturn(true);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "remember-player-count",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"playerReply\":\"记住了：以后默认按四个人玩；这次先不推荐。\","
                                    + "\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,"
                                    + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}")),
                    CompletionStatus.COMPLETE);
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
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.profile().playerCount().minimum()).isEqualTo(4);
        assertThat(response.profile().playerCount().maximum()).isEqualTo(4);
        assertThat(captured.get().toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
        assertThat(captured.get().messages().getFirst().content())
                .contains("submit any explicit memory update", "typed preferenceUpdates argument");

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
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly(
                "MODEL_PROTOCOL_FAILED:ACTION_PROTOCOL_INVALID",
                "UNAVAILABLE:MODEL_PROTOCOL_FAILED:ACTION_PROTOCOL_INVALID");

        loop.stopBoundedCalls();
    }

    @Test
    void usesOneNativeTypedDecisionBeforePublishingVerifiedCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        AtomicReference<Request> actionRequest = new AtomicReference<>();
        Game candidate = game(901, "Protocol Meadow", "协议草甸", List.of("Avery Stone"));

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            actionRequest.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "native-search",
                            BoardGameRecommendationAgent.SEARCH_TOOL,
                            "{\"titles\":[\"Protocol Meadow\"]}")),
                    CompletionStatus.COMPLETE);
        });
        when(tools.inspectTitles(List.of("Protocol Meadow"))).thenReturn(
                new BoardGameRecommendationTools.CatalogObservation(
                        ToolStatus.SUCCESS,
                        BoardGameRecommendationTools.ToolName.INSPECT_BGG_TITLES,
                        1,
                        List.of(candidate),
                        List.of(),
                        ""));
        String publication = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":901}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"修正工具选择后，我核对了这张候选卡。"},{"surface":"CARD","role":"WHY_FIT","bggId":901,"internalEvidenceIds":["B901:designers"],"text":"目录身份显示这款游戏由 Avery Stone 设计。"}]}
                """
                .strip();
        when(model.streamStructured(any(), eq("player"), any()))
                .thenAnswer(invocation -> publishStructured(invocation, publication));
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
                        "请认真推荐一款游戏，并给我可以继续操作的卡片。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(901);
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .contains(
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "RECOMMEND_GAMES")
                .noneMatch(action -> action.startsWith("REPAIR_MODEL_DECISION:"))
                .noneMatch(action -> action.startsWith("UNAVAILABLE:"));
        assertThat(actionRequest.get()).satisfies(request -> {
            assertThat(request.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.REQUIRED);
            assertThat(request.messages())
                    .extracting(BoardGameRecommendationModel.Message::content)
                    .anyMatch(message -> message.contains("choosing exactly one supplied typed action"));
        });

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsUnstructuredNativeReplyWithoutPublishingIt() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn("这段没有经过 typed reply action，不能直接发布。", List.of(), CompletionStatus.COMPLETE));
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
        assertThat(response.assistantMessage())
                .doesNotContain("typed reply action");
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly(
                "UNSTRUCTURED_EVIDENCE_REPLY",
                "UNAVAILABLE:UNSTRUCTURED_EVIDENCE_REPLY");
        verify(model, never()).streamStructured(any(), eq("player"), any());

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
        assertThat(response.harness().modelCalls()).isOne();
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("ASK_USER");
        assertThat(captured.get().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(BoardGameRecommendationAgent.ASK_TOOL);
        verify(model, never()).streamStructured(any(), eq("player"), any());

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
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "browse-first-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":2,\"offset\":0}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "browse-second-page",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":2,\"offset\":2}")),
                        CompletionStatus.COMPLETE));
        String firstPublication = """
                {"decision":{"requestedCount":2,"selections":[{"bggId":901},{"bggId":902}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"先看这两款；它们沿着同一个方向，但推进重心不同。"},{"surface":"CARD","role":"WHY_FIT","bggId":901,"internalEvidenceIds":["B901:playerCount"],"text":"它把探索节奏放在第一位。"},{"surface":"CARD","role":"WHY_FIT","bggId":902,"internalEvidenceIds":["B902:playerCount"],"text":"它提供了另一种推进方向。"}]}
                """
                .strip();
        String secondPublication = """
                {"decision":{"requestedCount":2,"selections":[{"bggId":903},{"bggId":904}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"这次换一批，不重复前两款；两款的取舍也不一样。"},{"surface":"CARD","role":"WHY_FIT","bggId":903,"internalEvidenceIds":["B903:playerCount"],"text":"它延续条件但换了决策重心。"},{"surface":"CARD","role":"WHY_FIT","bggId":904,"internalEvidenceIds":["B904:playerCount"],"text":"它是同一方向的另一种取舍。"}]}
                """
                .strip();
        when(model.streamStructured(any(), eq("player"), any()))
                .thenAnswer(invocation -> publishStructured(invocation, firstPublication))
                .thenAnswer(invocation -> publishStructured(invocation, secondPublication));
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
        assertThat(first.harness().modelCalls()).isEqualTo(2);
        assertThat(second.harness().modelCalls()).isEqualTo(2);
        assertThat(second.harness().actions()).containsExactly("SEARCH_BGG_CATALOG", "RECOMMEND_GAMES");

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
                                "tabletop.example.test")),
                        new ResolvedRelationship(
                                RelationshipKind.DESIGNER,
                                "Studio Architect",
                                List.of(1))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-local-wrong-identity",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"designers\":[\"Wrong Architect\"],\"purpose\":\"IDENTITY_ONLY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Studio Architect alias\",\"afterIdentity\":\"RECOMMEND_WITH_CARDS\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-filter",
                                BoardGameRecommendationAgent.BROWSE_TOOL,
                                "{\"designers\":[\"Studio Architect\"],\"limit\":2}")),
                        CompletionStatus.COMPLETE));
        String publication = """
                {"decision":{"requestedCount":2,"selections":[{"bggId":701},{"bggId":702}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"这两款都来自已核对的设计师关系，可以从不同结构方向认识他的设计。"},{"surface":"CARD","role":"WHY_FIT","bggId":701,"internalEvidenceIds":["B701:designers"],"text":"先从它看这位设计师如何组织核心循环。"},{"surface":"CARD","role":"WHY_FIT","bggId":702,"internalEvidenceIds":["B702:designers"],"text":"它提供了同一设计师的另一种结构方向。"}]}
                """
                .strip();
        when(model.streamStructured(any(), eq("player"), any()))
                .thenAnswer(invocation -> publishStructured(invocation, publication));
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
                                + "我要从其中选择《Anchor Workshop》继续读规则书、听讲解并答疑。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(designerQuery).hasValue("Studio Architect");
        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(701, 702);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains(
                        "SEARCH_BGG_CATALOG",
                        "DISCOVER_CANDIDATES",
                        "DISCOVERY_RELATIONSHIP_VERIFIED",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void publishesSourceBackedFictionalFranchiseTitlesWhenDiscoveryNeedsSelectableCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        AtomicReference<DiscoveryRequest> capturedDiscovery = new AtomicReference<>();
        AtomicReference<Request> continuationRequest = new AtomicReference<>();
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
                                "tabletop.example.test")),
                        new ResolvedRelationship(RelationshipKind.OTHER, "Orion Saga", List.of(1))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player")))
                .thenReturn(new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-franchise",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Orion Saga IP\",\"afterIdentity\":\"RECOMMEND_WITH_CARDS\",\"candidateUse\":\"CONTINUE_REACT\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    continuationRequest.set(invocation.getArgument(0));
                    return new Turn(
                            "",
                            List.of(new ToolCall(
                                    "call-publish-franchise-titles",
                                    BoardGameRecommendationAgent.SEARCH_TOOL,
                                    "{\"titles\":[\"Orion Frontier\",\"Orion Rebellion\"],\"candidateUse\":\"PUBLISH_CARDS\"}")),
                            CompletionStatus.COMPLETE);
                });
        String publication = """
                {"decision":{"requestedCount":2,"selections":[{"bggId":711},{"bggId":712}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":711,"internalEvidenceIds":["R711:1"],"text":"公开系列资料把《Orion Frontier》列为这个 IP 的桌游作品。"},{"surface":"MESSAGE","role":"NARRATIVE","bggId":712,"internalEvidenceIds":["R712:1"],"text":"同一份系列资料也把《Orion Rebellion》列为这个 IP 的桌游作品。"},{"surface":"CARD","role":"WHY_FIT","bggId":711,"internalEvidenceIds":["B711:designers"],"text":"这款已核对的候选由 Morgan Vale 设计。"},{"surface":"CARD","role":"WHY_FIT","bggId":712,"internalEvidenceIds":["B712:designers"],"text":"这款已核对的候选由 Riley North 设计。"}]}
                """
                .strip();
        when(model.streamStructured(any(), eq("player"), any()))
                .thenAnswer(invocation -> publishStructured(invocation, publication));
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
                        "‘Orion Saga’这个虚构科幻 IP 有哪些桌游？请给我两款可选择的卡片。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(capturedDiscovery.get()).satisfies(request -> {
            assertThat(request.subject()).isEqualTo("Orion Saga IP");
            assertThat(request.goal()).isEqualTo(DiscoveryGoal.SELECTABLE_CARDS);
        });
        assertThat(continuationRequest.get().tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .as("a selectable verified IP slate cannot terminate as cardless prose")
                .doesNotContain(BoardGameRecommendationAgent.REPLY_TOOL)
                .contains(BoardGameRecommendationAgent.SEARCH_TOOL);
        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(entry -> entry.game().ranking().bggId())
                .containsExactly(711, 712);
        assertThat(response.researchSources()).singleElement().satisfies(source -> {
            assertThat(source.url()).isEqualTo("https://tabletop.example.test/orion-saga");
            assertThat(source.domain()).isEqualTo("tabletop.example.test");
        });
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains(
                        "DISCOVER_CANDIDATES",
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "DISCOVERY_RELATIONSHIP_REJECTED:MISSING_OR_OTHER",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void keepsIdentityOnlyDiscoveryOutOfCardPublication() {
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
        String unresolvedReply = "这个称呼目前没有足够的公开证据，我不想把它猜成某个系列或设计师。";
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-identity",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"Silver Comet alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-finish-unresolved-identity",
                                BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL,
                                "{\"status\":\"UNRESOLVED\",\"playerReply\":\"" + unresolvedReply + "\"}")),
                        CompletionStatus.COMPLETE));
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
                .contains("DISCOVER_CANDIDATES", "REPLY_TO_USER:IDENTITY_UNRESOLVED")
                .doesNotContain("RECOMMEND_GAMES", "RESEARCH_GAME_FIT");
        verify(model, never()).streamStructured(any(), eq("player"), any());

        loop.stopBoundedCalls();
    }

    @Test
    void appliesExplicitHardPreferencesOnTheCandidateReadBeforeSelectingCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        AtomicReference<Request> actionRequest = new AtomicReference<>();
        AtomicReference<Request> publicationRequest = new AtomicReference<>();
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
            actionRequest.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-browse",
                            BoardGameRecommendationAgent.BROWSE_TOOL,
                            """
                            {"purpose":"SELECTABLE_CARDS","limit":8,"preferenceUpdates":[
                              {"field":"playerCount","value":4,"evidence":"U1","evidenceClassification":"DIRECT"},
                              {"field":"durationMinutes","value":{"minimum":null,"maximum":75},"evidence":"U1","evidenceClassification":"DIRECT"}
                            ]}
                            """)),
                    CompletionStatus.COMPLETE);
        });
        String publication = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":801}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"先看这一款：目录时长落在今晚的上限内，四个人也能坐下。"},{"surface":"CARD","role":"WHY_FIT","bggId":801,"internalEvidenceIds":["B801:durationMinutes"],"text":"能在今晚的时间上限内完整收尾。"}]}
                """
                .strip();
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            publicationRequest.set(invocation.getArgument(0));
            return publishStructured(invocation, publication);
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
        assertThat(response.harness().modelCalls()).isEqualTo(2);
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
        assertThat(browseSchema).contains("preferenceUpdates");
        assertThat(publicationRequest.get()).satisfies(request -> {
            assertThat(request.tools()).isEmpty();
            assertThat(request.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.NONE);
            assertThat(request.structuredOutput().name()).isEqualTo("recommendation_publication");
            assertThat(request.structuredOutput().jsonSchema())
                    .contains("decision", "replyBlocks")
                    .doesNotContain("preferenceUpdates", "recommend_games");
        });

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
                        new ResolvedRelationship(
                                RelationshipKind.DESIGNER,
                                "Studio Architect",
                                List.of(1))));
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
                                "{\"evidence\":\"U2\",\"subject\":\"Studio Architect alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                        CompletionStatus.COMPLETE))
                .thenAnswer(invocation -> {
                    followupRequest.set(invocation.getArgument(0));
                    return new Turn(
                            "",
                            List.of(new ToolCall(
                                    "call-finish-identity",
                                    BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL,
                                    "{\"status\":\"VERIFIED\",\"entityKind\":\"DESIGNER\",\"entityNames\":[\"Studio Architect\"],\"playerReply\":\""
                                            + naturalReply
                                            + "\"}")),
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

        List<String> publishedAnswerParts = new ArrayList<>();
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
                ignored -> {},
                publishedAnswerParts::add);

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo(naturalReply);
        assertThat(publishedAnswerParts)
                .as("only the validated terminal reply may reach the player stream")
                .containsExactly(naturalReply);
        assertThat(response.assistantMessage()).doesNotContain("Anchor Workshop");
        assertThat(response.harness().actions())
                .contains("REPLY_TO_USER")
                .noneMatch(action -> action.startsWith("REJECTED_"));
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        Request observedFollowup = followupRequest.get();
        assertThat(observedFollowup.messages().get(1).content())
                .contains("上次我们聊到重度欧式", "记得，你更在意机制之间的联动", "齿轮先生");
        assertThat(observedFollowup.messages().get(2).content()).isEmpty();
        assertThat(observedFollowup.messages().get(3).content())
                .contains("discoveredRelationship", "Studio Architect", "verifiedGames")
                .doesNotContain("UNTRUSTED WRONG DRAFT");
        verify(model, never()).streamStructured(any(), eq("player"), any());

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
        String unresolvedReply = "这个叫法我还不能可靠地缩小到某一位，所以不想随便猜。"
                + "《银河漫游》的 BGG 署名是 Avery Stone、Blake North 和 Casey Rivers；目前更稳妥的说法，是线索只指向这组设计团队。";
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"the creator alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-resolve-context",
                                BoardGameRecommendationAgent.RESOLVE_TOOL,
                                "{\"title\":\"银河漫游\",\"purpose\":\"COMPARISON_REFERENCE\",\"evidence\":\"U1\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-finish-unresolved",
                                BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL,
                                "{\"status\":\"UNRESOLVED\",\"contextBggIds\":[703],\"playerReply\":\""
                                        + unresolvedReply
                                        + "\"}")),
                        CompletionStatus.COMPLETE));
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
        assertThat(response.harness().modelCalls()).isEqualTo(3);
        assertThat(response.harness().webResearchCalls()).isEqualTo(1);
        assertThat(response.harness().actions())
                .containsSubsequence(
                        "DISCOVER_CANDIDATES",
                        "RESOLVE_BGG_REFERENCE",
                        "REPLY_TO_USER:IDENTITY_UNRESOLVED")
                .doesNotContain("RESEARCH_GAME_FIT");
        verify(model, never()).streamStructured(any(), eq("player"), any());

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
                        new ResolvedRelationship(RelationshipKind.DESIGNER_GROUP, designers, List.of(1))));
            }
        };
        when(model.configured("player")).thenReturn(true);
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-discover-group",
                                BoardGameRecommendationAgent.DISCOVER_TOOL,
                                "{\"evidence\":\"U1\",\"subject\":\"the team alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-finish-group",
                                BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL,
                                "{\"status\":\"VERIFIED\",\"entityKind\":\"DESIGNER_GROUP\",\"entityNames\":[\"Avery Stone\",\"Blake North\",\"Casey Rivers\"],\"playerReply\":\"知道，这个称呼说的不是某一位，而是 Avery Stone、Blake North 和 Casey Rivers 这组设计搭档。你要是感兴趣，我们还可以继续聊他们共同作品里的设计取向。\"}")),
                        CompletionStatus.COMPLETE));
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
        assertThat(response.assistantMessage())
                .isEqualTo("知道，这个称呼说的不是某一位，而是 Avery Stone、Blake North 和 Casey Rivers 这组设计搭档。你要是感兴趣，我们还可以继续聊他们共同作品里的设计取向。");
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions()).contains("REPLY_TO_USER");
        verify(model, never()).streamStructured(any(), eq("player"), any());

        loop.stopBoundedCalls();
    }

    private StructuredTurn publishStructured(InvocationOnMock invocation, String payload) {
        @SuppressWarnings("unchecked")
        Consumer<String> listener = invocation.getArgument(2);
        listener.accept(payload);
        return new StructuredTurn(payload, CompletionStatus.COMPLETE);
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
