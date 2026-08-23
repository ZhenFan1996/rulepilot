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
            @SuppressWarnings("unchecked")
            Consumer<String> listener = invocation.getArgument(2);
            captured.set(request);
            listener.accept(answer.substring(0, 12));
            listener.accept(answer);
            return new Turn(answer, List.of(), CompletionStatus.COMPLETE);
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
        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_FAST_PATH);
        assertThat(response.assistantMessage()).isEqualTo(answer);
        assertThat(streamed).containsExactly(answer.substring(0, 12), answer);
        assertThat(response.harness().modelCalls()).isEqualTo(1);
        assertThat(response.harness().catalogCalls()).isZero();
        assertThat(response.harness().webResearchCalls()).isZero();
        assertThat(response.harness().actions()).containsExactly("STREAMED_DIRECT_AGENT_REPLY");

        Request first = captured.get();
        assertThat(first).isNotNull();
        assertThat(first.tools())
                .extracting(BoardGameRecommendationModel.ToolSpec::name)
                .contains(
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        BoardGameRecommendationAgent.ASK_TOOL,
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        BoardGameRecommendationAgent.DISCOVER_TOOL);
        var toolSchemas = first.tools().stream().collect(Collectors.toMap(
                BoardGameRecommendationModel.ToolSpec::name,
                BoardGameRecommendationModel.ToolSpec::inputSchema));
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.SEARCH_TOOL))
                .doesNotContain("preferenceUpdates");
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.DISCOVER_TOOL))
                .doesNotContain("preferenceUpdates");
        assertThat(toolSchemas.get(BoardGameRecommendationAgent.BROWSE_TOOL))
                .contains("purpose", "categories", "mechanics", "designers");
        assertThat(first.tools().stream()
                        .filter(tool -> BoardGameRecommendationAgent.BROWSE_TOOL.equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .description())
                .contains("local BGG catalog without public web latency");
        String system = first.messages().getFirst().content();
        assertThat(system)
                .contains(
                        "Decide the route yourself",
                        "long-lived local verified-relationship cache first",
                        "Plan across several reads",
                        "reuse the exact observed designer")
                .doesNotContain("Direct prose is for conversation that needs no external information")
                .hasSizeLessThan(3_500);
        String userInput = first.messages().getLast().content();
        assertThat(userInput)
                .contains("recentConversation", "齿轮先生")
                .doesNotContain("executionBudget", "availableCapabilities", "\"goal\"");

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
                                "{\"evidence\":\"U1\",\"purpose\":\"IDENTITY_ONLY\"}")),
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
                new ConversationRequest(RecommendationProfile.empty(), "我想玩那位‘齿轮先生’设计的桌游，给我两款。"),
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
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES",
                        "SEARCH_BGG_CATALOG",
                        "RECOMMEND_GAMES");

        loop.stopBoundedCalls();
    }

    @Test
    void identityAnswerCannotReplaceTheVerifiedDesignerWithTheEvidenceCarrierGame() {
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
        when(model.configured("player")).thenReturn(true);
        when(model.streamNext(any(), eq("player"), any())).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "call-discover",
                        BoardGameRecommendationAgent.DISCOVER_TOOL,
                        "{\"evidence\":\"U1\",\"purpose\":\"IDENTITY_ONLY\"}")),
                CompletionStatus.COMPLETE));
        when(model.next(any(), eq("player"))).thenReturn(
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-wrong-identity",
                                BoardGameRecommendationAgent.REPLY_TOOL,
                                "{\"entityKind\":\"GAME\",\"entityName\":\"Anchor Workshop\"}")),
                        CompletionStatus.COMPLETE),
                new Turn(
                        "",
                        List.of(new ToolCall(
                                "call-correct-identity",
                                BoardGameRecommendationAgent.REPLY_TOOL,
                                "{\"entityKind\":\"DESIGNER\",\"entityName\":\"Studio Architect\"}")),
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
                new ConversationRequest(RecommendationProfile.empty(), "你知道桌游圈里的‘齿轮先生’吗？"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.assistantMessage()).isEqualTo("这个称呼通常指桌游设计师 Studio Architect。");
        assertThat(response.assistantMessage()).doesNotContain("Anchor Workshop");
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:IDENTITY_NOT_VERIFIED", "REPLY_TO_USER");
        assertThat(response.harness().modelCalls()).isEqualTo(3);

        loop.stopBoundedCalls();
    }

    private Game game(int id, String name, String designer) {
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
                        "",
                        "",
                        1,
                        4,
                        90,
                        new BigDecimal("3.6"),
                        List.of("Strategy"),
                        List.of("Hand Management"),
                        60,
                        90,
                        12,
                        12,
                        "3",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(designer),
                        List.of("Studio Publisher"),
                        "A strategy game with interlocking actions.",
                        ""));
    }
}
