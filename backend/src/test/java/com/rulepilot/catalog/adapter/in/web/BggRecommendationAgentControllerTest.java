package com.rulepilot.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.application.BggMetadataLocalizationService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedDiscoveryTaxonomy;
import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.ClarificationOption;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BggRecommendationAgentControllerTest {

    private final BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
    private final BggMetadataLocalizationService localization = mock(BggMetadataLocalizationService.class);
    private final BggRecommendationAgentController controller =
            new BggRecommendationAgentController(agent, localization);

    @Test
    void exposesTheTypedClarificationAndNormalizedProfile() {
        RecommendationProfile profile = new RecommendationProfile(
                4, null, null, GameType.ALL, InteractionPreference.ANY);
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
        when(localization.localizeDiscoveryTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedDiscoveryTaxonomy(Map.of(), Map.of(), false));

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
                4, 90, new BigDecimal("3.2"), GameType.STRATEGY, InteractionPreference.COOPERATIVE);
        RankedGame ranked = new RankedGame(
                266192,
                "Wingspan",
                2019,
                34,
                new BigDecimal("7.79"),
                new BigDecimal("8.09"),
                102_030,
                false,
                Map.of(GameType.STRATEGY, 50));
        DiscoveryGame details = new DiscoveryGame(
                1,
                266192,
                "Wingspan",
                "展翅翱翔",
                2019,
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                new BigDecimal("8.09"),
                new BigDecimal("2.5"),
                List.of("Animals"),
                List.of("Card Drafting"));
        when(agent.converse(any(), eq("zh-CN"))).thenReturn(new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                "下面这些各有侧重。",
                profile,
                null,
                179_737,
                20,
                List.of(new RecommendedGame(
                        new BrowseGame(ranked, null, details),
                        List.of("支持 4 人游玩"),
                        List.of()))));
        when(localization.localizeDiscoveryTaxonomy(List.of("Animals"), List.of("Card Drafting"), "zh-CN"))
                .thenReturn(new LocalizedDiscoveryTaxonomy(
                        Map.of("Animals", "动物"), Map.of("Card Drafting", "卡牌轮抽"), true));

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
