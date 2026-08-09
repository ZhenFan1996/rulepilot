package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationSelectorTest {

    @Test
    void explainsReferenceSimilarityWithTheObservedSharedFeature() {
        BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(
                new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.66")));
        Game candidate = new Game(
                new Ranking(30, "River Guild", 2024, 40, new BigDecimal("7.4"), new BigDecimal("7.7"), 800),
                new Details(
                        "River Guild",
                        "",
                        "",
                        2,
                        4,
                        75,
                        new BigDecimal("2.7"),
                        List.of("Economic"),
                        List.of("Card Drafting", "Worker Placement"),
                        60,
                        75,
                        12,
                        10,
                        "3",
                        "2-4",
                        2,
                        200,
                        List.of(),
                        List.of(),
                        List.of()));
        RetrievalPlan plan = new RetrievalPlan(
                List.of(),
                List.of(new FeatureConstraint(
                        "Card Drafting",
                        FeatureMode.PREFERRED,
                        FeatureSource.BGG_METADATA,
                        "reference: White Courtyard")),
                true);

        var pool = selector.prepare(
                List.of(candidate),
                BoardGameRecommendationAgent.RecommendationProfile.empty(),
                List.of(),
                plan,
                List.of(30));
        var result = selector.fallback(
                pool, BoardGameRecommendationAgent.RecommendationProfile.empty(), true);

        assertThat(result).singleElement().satisfies(game ->
                assertThat(game.matches())
                        .contains("与参考游戏共享 BGG 记录的机制或类型“Card Drafting”")
                        .noneMatch(value -> value.contains("current game") || value.contains("BGG 总榜")));
    }
}
