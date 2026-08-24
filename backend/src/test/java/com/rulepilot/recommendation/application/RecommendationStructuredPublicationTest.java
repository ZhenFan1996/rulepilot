package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredTurn;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolName;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecommendationStructuredPublicationTest {

    @Test
    void validatesTheDecisionThenStreamsCompleteNaturalBlocksWithoutAdvertisingATerminalTool() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        Game game = game(101);
        AtomicReference<Request> structuredRequest = new AtomicReference<>();
        List<String> streamed = new ArrayList<>();
        String firstParagraph = "先给你一个明确答案：我会从《Signal Grove》开始。";
        String secondParagraph = "它是合作游戏，三个人会围绕同一目标讨论。";
        String payload = """
                {"decision":{"requestedCount":1,"selections":[{"bggId":101}],"referenceBggIds":[]},"replyBlocks":[{"surface":"MESSAGE","role":"NARRATIVE","bggId":null,"internalEvidenceIds":[],"text":"先给你一个明确答案：我会从《Signal Grove》开始。"},{"surface":"MESSAGE","role":"NARRATIVE","bggId":101,"internalEvidenceIds":["B101:mechanics"],"text":"它是合作游戏，三个人会围绕同一目标讨论。"},{"surface":"CARD","role":"WHY_FIT","bggId":101,"internalEvidenceIds":["B101:mechanics"],"text":"合作机制让三个人始终围绕共同目标交流。"},{"surface":"CARD","role":"TRADEOFF","bggId":101,"internalEvidenceIds":["B101:complexity"],"text":"复杂度 2.8 不算重，但仍需要先讲清核心流程。"}]}
                """
                .strip();

        when(model.configured("player")).thenReturn(true);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.inspectTitles(List.of("Signal Grove"))).thenReturn(new CatalogObservation(
                ToolStatus.SUCCESS,
                ToolName.INSPECT_BGG_TITLES,
                1,
                List.of(game),
                List.of(),
                ""));
        when(model.streamDecision(any(), eq("player"), any())).thenReturn(new Turn(
                "",
                List.of(new ToolCall(
                        "inspect-candidates",
                        BoardGameRecommendationAgent.SEARCH_TOOL,
                        "{\"titles\":[\"Signal Grove\"]}")),
                CompletionStatus.COMPLETE));
        when(model.streamStructured(any(), eq("player"), any())).thenAnswer(invocation -> {
            structuredRequest.set(invocation.getArgument(0));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> listener = invocation.getArgument(2);
            int decisionEnd = payload.indexOf(",\"replyBlocks\"") + 1;
            int firstBlockEnd = payload.indexOf("},{\"surface\":\"MESSAGE\"", decisionEnd) + 1;
            int secondBlockEnd = payload.indexOf("},{\"surface\":\"CARD\"", firstBlockEnd) + 1;
            listener.accept(payload.substring(0, decisionEnd));
            listener.accept(payload.substring(decisionEnd, firstBlockEnd));
            listener.accept(payload.substring(firstBlockEnd, secondBlockEnd));
            listener.accept(payload.substring(secondBlockEnd));
            return new StructuredTurn(payload, CompletionStatus.COMPLETE);
        });
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
                        "我们三个人，想合作但不想太重。先认真推荐一款，并告诉我取舍。"),
                "zh-CN",
                "player",
                ignored -> {},
                streamed::add);

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(streamed).containsExactly(firstParagraph, firstParagraph + "\n\n" + secondParagraph);
        assertThat(response.assistantMessage()).isEqualTo(streamed.getLast());
        assertThat(response.recommendationLead()).isEqualTo(response.assistantMessage());
        assertThat(response.games()).singleElement().satisfies(recommended -> {
            assertThat(recommended.game().ranking().bggId()).isEqualTo(101);
            assertThat(recommended.replyParts())
                    .extracting(part -> part.role().name())
                    .containsExactly("WHY_FIT", "TRADEOFF");
        });
        assertThat(response.harness().modelCalls()).isEqualTo(2);
        assertThat(structuredRequest.get()).satisfies(request -> {
            assertThat(request.tools()).isEmpty();
            assertThat(request.toolChoice()).isEqualTo(BoardGameRecommendationModel.ToolChoice.NONE);
            assertThat(request.structuredOutput().name()).isEqualTo("recommendation_publication");
            assertThat(request.structuredOutput().jsonSchema())
                    .contains("decision", "replyBlocks", "internalEvidenceIds");
        });

        loop.stopBoundedCalls();
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove",
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Signal Grove",
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.8"),
                        List.of("Strategy"),
                        List.of("Cooperative Game"),
                        45,
                        60,
                        10,
                        10,
                        "4",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of(),
                        "Players restore paths through the grove.",
                        ""));
    }
}
