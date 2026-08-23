package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.RelationshipKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.ResolvedRelationship;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RecommendationNaturalFrontDoorTest {

    @Test
    void streamsStableBoardGameConversationWhileSeeingTheCompleteAgentToolSet() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        String answer = "如果你说的是桌游圈里的这个昵称，我知道；它指一位以重度设计闻名的设计师。";

        when(tools.webResearchConfigured()).thenReturn(true);
        when(model.configured("player")).thenReturn(true);
        when(model.streamNext(any(), eq("player"), any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            captured.set(request);
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-reply",
                            BoardGameRecommendationAgent.REPLY_TOOL,
                            "{\"playerReply\":\"" + answer + "\"}")),
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
                new ConversationRequest(RecommendationProfile.empty(), "你知道桌游圈里的‘齿轮先生’吗？"),
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
                        BoardGameRecommendationAgent.DISCOVER_TOOL)
                .doesNotContain(BoardGameRecommendationAgent.ASK_TOOL);
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
                .contains("recentConversation", "齿轮先生")
                .doesNotContain("executionBudget", "availableCapabilities", "\"goal\"");

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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(
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
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "recommend-first-page",
                                BoardGameRecommendationAgent.RECOMMEND_TOOL,
                                "{\"selections\":[{\"bggId\":901,\"reason\":\"它把探索节奏放在第一位。\"},{\"bggId\":902,\"reason\":\"它提供了另一种推进方向。\"}],\"requestedCount\":2,\"playerReply\":\"先看这两款。\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "recommend-second-page",
                                BoardGameRecommendationAgent.RECOMMEND_TOOL,
                                "{\"selections\":[{\"bggId\":903,\"reason\":\"它延续条件但换了决策重心。\"},{\"bggId\":904,\"reason\":\"它是同一方向的另一种取舍。\"}],\"requestedCount\":2,\"playerReply\":\"这次换一批，不重复前两款。\"}")),
                        CompletionStatus.COMPLETE));
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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "call-local-wrong-identity",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"designers\":[\"Wrong Architect\"],\"purpose\":\"IDENTITY_ONLY\"}")),
                CompletionStatus.COMPLETE));
        when(model.next(any(), eq("player"))).thenReturn(
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
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-recommend",
                                BoardGameRecommendationAgent.RECOMMEND_TOOL,
                                "{\"selections\":[{\"bggId\":701,\"reason\":\"先从它看这位设计师如何组织核心循环。\"},{\"bggId\":702,\"reason\":\"它提供了同一设计师的另一种结构方向。\"}],\"requestedCount\":2,\"playerReply\":\"这两款都来自已核对的设计师关系。\"}")),
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
    void appliesExplicitHardPreferencesOnTheCandidateReadBeforeSelectingCards() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        AtomicReference<Request> recommendationRequest = new AtomicReference<>();
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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
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
                CompletionStatus.COMPLETE));
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
            recommendationRequest.set(invocation.getArgument(0));
            return new Turn(
                    "",
                    List.of(new ToolCall(
                            "call-recommend",
                            BoardGameRecommendationAgent.RECOMMEND_TOOL,
                            """
                            {"selections":[{"bggId":801,"reason":"能在今晚的时间上限内完整收尾。"}],"requestedCount":1,"playerReply":"先看这一款。"}
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
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(response.harness().actions())
                .containsSubsequence("UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG", "RECOMMEND_GAMES")
                .doesNotContain("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
        String recommendationSchema = recommendationRequest.get().tools().stream()
                .filter(tool -> BoardGameRecommendationAgent.RECOMMEND_TOOL.equals(tool.name()))
                .findFirst()
                .orElseThrow()
                .inputSchema();
        assertThat(recommendationSchema).doesNotContain("preferenceUpdates");

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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
                "UNTRUSTED WRONG DRAFT",
                List.of(new ToolCall(
                        "call-discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"evidence\":\"U2\",\"subject\":\"Studio Architect alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                CompletionStatus.COMPLETE));
        String naturalReply = "知道，你说的是 Studio Architect。这个圈内叫法还挺形象的；如果你愿意，我们可以接着聊聊他的作品有什么共同气质。";
        when(model.next(any(), eq("player"))).thenAnswer(invocation -> {
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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "call-discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"evidence\":\"U1\",\"subject\":\"the creator alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                CompletionStatus.COMPLETE));
        String unresolvedReply = "这个叫法我还不能可靠地缩小到某一位，所以不想随便猜。"
                + "《银河漫游》的 BGG 署名是 Avery Stone、Blake North 和 Casey Rivers；目前更稳妥的说法，是线索只指向这组设计团队。";
        when(model.next(any(), eq("player"))).thenReturn(
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
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "call-discover-group",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"evidence\":\"U1\",\"subject\":\"the team alias\",\"afterIdentity\":\"REPLY_WITH_IDENTITY\"}")),
                CompletionStatus.COMPLETE));
        when(model.next(any(), eq("player"))).thenReturn(new Turn(
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

        loop.stopBoundedCalls();
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
