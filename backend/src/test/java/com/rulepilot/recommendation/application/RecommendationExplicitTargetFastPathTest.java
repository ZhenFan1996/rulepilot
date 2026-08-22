package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationExplicitTargetFastPathTest {

    @Test
    void publishesAnExactLocalCardWithoutWaitingForAPlanningModel() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        Game game = game(42001, "Harbor Nova", "星港");
        Game remembered = game(42000, "Old Harbor", "旧港");
        RecommendedGame card = new RecommendedGame(game, List.of(), List.of());
        when(tools.webResearchConfigured()).thenReturn(false);
        when(tools.resolveLocalReferenceTitle("星港"))
                .thenReturn(new ReferenceObservation(ToolStatus.SUCCESS, List.of(game), ""));
        when(selector.present(
                        eq(List.of(game)),
                        any(RecommendationProfile.class),
                        eq(List.of()),
                        eq(true),
                        any(Research.class)))
                .thenReturn(List.of(card));
        RecommendationReActLoop loop = loop(model, tools, selector);

        var response = loop.converse(
                request(
                        "我们今晚第一次玩星港，规则书还没看。能帮我把这款找出来，然后带我们读规则吗？",
                        List.of(remembered)),
                "zh-CN",
                null,
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.mode()).isEqualTo(DecisionMode.MODEL_FAST_PATH);
        assertThat(response.games()).containsExactly(card);
        assertThat(response.assistantMessage()).contains("星港", "规则书", "讲解");
        assertThat(response.harness().modelCalls()).isZero();
        assertThat(response.harness().catalogCalls()).isEqualTo(1);
        assertThat(response.candidatesEvaluated())
                .as("the current-turn count must not include remembered candidates from older turns")
                .isEqualTo(1);
        assertThat(response.harness().actions())
                .containsExactly("RESOLVE_EXPLICIT_TARGET_LOCALLY", "RECOMMEND_GAMES");
        verify(model, never()).configured(any());
        verify(model, never()).streamNext(any(), any(), any());
    }

    @Test
    void doesNotTurnAMultiTitleComparisonIntoAnExactPick() {
        BoardGameRecommendationModel model = mock(BoardGameRecommendationModel.class);
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        BoardGameRecommendationSelector selector = mock(BoardGameRecommendationSelector.class);
        when(tools.webResearchConfigured()).thenReturn(false);
        when(model.configured(null)).thenReturn(false);
        RecommendationReActLoop loop = loop(model, tools, selector);

        var response = loop.converse(
                request("我们已选定《Harbor Nova》和《Loom City》做比较。"),
                "zh-CN",
                null,
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verify(tools, never()).resolveLocalReferenceTitle(any());
    }

    private RecommendationReActLoop loop(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector) {
        return new RecommendationReActLoop(
                model,
                tools,
                selector,
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30)),
                new ObjectMapper());
    }

    private ConversationRequest request(String message) {
        return request(message, List.of());
    }

    private ConversationRequest request(String message, List<Game> priorVerifiedGames) {
        return new ConversationRequest(
                RecommendationProfile.empty(),
                message,
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                priorVerifiedGames);
    }

    private Game game(int bggId, String sourceName, String chineseName) {
        return new Game(
                new Ranking(
                        bggId,
                        sourceName,
                        2024,
                        10,
                        new BigDecimal("8.1"),
                        new BigDecimal("8.2"),
                        2_000),
                new Details(
                        sourceName,
                        chineseName,
                        "https://example.invalid/cover.jpg",
                        1,
                        4,
                        60,
                        new BigDecimal("2.4"),
                        List.of(),
                        List.of(),
                        45,
                        75,
                        10,
                        12,
                        "2",
                        "2-4",
                        null,
                        500,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
