package com.rulepilot.recommendation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ClarificationOption;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BggRecommendationAgentControllerTest {

    private final BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
    private final BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
    private final BggRecommendationAgentController controller =
            new BggRecommendationAgentController(agent, presentation);

    @Test
    void exposesTheTypedClarificationAndNormalizedProfile() {
        RecommendationProfile profile = new RecommendationProfile(
                4, null, null, BggGameType.ALL, InteractionPreference.ANY);
        when(agent.converse(any(), eq("zh-CN"))).thenReturn(new ConversationResponse(
                Outcome.NEEDS_CLARIFICATION,
                DecisionMode.DETERMINISTIC,
                "你们愿意为一局留出多长时间？",
                profile,
                new Clarification(
                        PreferenceField.DURATION,
                        "你们愿意为一局留出多长时间？",
                        List.of(new ClarificationOption("60", "1 小时内"))),
                0,
                0,
                List.of()));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));

        var response = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(
                        new BggRecommendationAgentController.RecommendationProfileRequest(
                                4, null, null, "all", "any"),
                        ""),
                "zh-CN");

        assertThat(response.outcome()).isEqualTo("needs_clarification");
        assertThat(response.profile().players()).isEqualTo(4);
        assertThat(response.clarification().field()).isEqualTo("duration");
        assertThat(response.clarification().options().getFirst().label()).isEqualTo("1 小时内");
    }

    @Test
    void returnsAttributedCardsWithOfficialChineseNamesAndTranslatedTaxonomy() {
        RecommendationProfile profile = new RecommendationProfile(
                4, 90, new BigDecimal("3.2"), BggGameType.STRATEGY, InteractionPreference.COOPERATIVE);
        Ranking ranked = new Ranking(
                266192,
                "Wingspan",
                2019,
                34,
                new BigDecimal("7.79"),
                new BigDecimal("8.09"),
                102_030);
        Details details = new Details(
                "Wingspan",
                "展翅翱翔",
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                new BigDecimal("2.5"),
                List.of("Animals"),
                List.of("Card Drafting"),
                40,
                70,
                10,
                10,
                "3",
                "2-4",
                2,
                1_000,
                List.of(),
                List.of(),
                List.of());
        when(agent.converse(any(), eq("zh-CN"))).thenReturn(new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                "下面这些各有侧重。",
                profile,
                null,
                179_737,
                20,
                List.of(new RecommendedGame(
                        new Game(ranked, details),
                        List.of("支持 4 人游玩"),
                        List.of()))));
        when(presentation.localizeTaxonomy(List.of("Animals"), List.of("Card Drafting"), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(
                        Map.of("Animals", "动物"), Map.of("Card Drafting", "卡牌轮抽")));
        when(presentation.usesSimplifiedChinese("zh-CN")).thenReturn(true);

        var response = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(null, "四个人一起玩"),
                "zh-CN");

        assertThat(response.mode()).isEqualTo("model_assisted");
        assertThat(response.sourceCount()).isEqualTo(179_737);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().name()).isEqualTo("展翅翱翔");
            assertThat(game.game().originalName()).isEqualTo("Wingspan");
            assertThat(game.game().nameLocalized()).isTrue();
            assertThat(game.game().categories()).containsExactly("动物");
            assertThat(game.game().mechanics()).containsExactly("卡牌轮抽");
            assertThat(game.matches()).containsExactly("支持 4 人游玩");
        });
    }
}
