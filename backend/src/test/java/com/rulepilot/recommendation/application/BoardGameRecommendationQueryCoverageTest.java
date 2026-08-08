package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationQueryCoverageTest {

    private final BoardGameRecommendationQueryCoverage coverage = new BoardGameRecommendationQueryCoverage();

    @Test
    void preservesAnUnmappedQualitativeRequestWithoutInterpretingItAsBggMetadata() {
        RetrievalPlan completed = coverage.preserveUncoveredExpression(
                RetrievalPlan.empty(), "推荐一些适合4人、2小时左右的区控游戏");

        assertThat(completed.candidateDiscoveryRequested()).isTrue();
        assertThat(completed.features()).singleElement().satisfies(feature -> {
            assertThat(feature.term()).isEqualTo("区控");
            assertThat(feature.mode()).isEqualTo(FeatureMode.REQUIRED);
            assertThat(feature.source()).isEqualTo(FeatureSource.USER_EXPRESSION);
            assertThat(feature.basedOn()).isEqualTo("推荐一些适合4人、2小时左右的区控游戏");
        });
    }

    @Test
    void handlesDifferentThemeAndExperienceLanguageWithoutAProductVocabularyList() {
        assertThat(coverage.uncoveredExpression("想找海盗主题，不要太依赖文字", List.of()))
                .isEqualTo("海盗主题 不要太依赖文字");
        assertThat(coverage.uncoveredExpression("low downtime for 5 players", List.of()))
                .isEqualTo("low downtime");
    }

    @Test
    void doesNotInventAResidualWhenAllQualitativeLanguageIsAlreadyMapped() {
        var mapped = new FeatureConstraint(
                "Science Fiction", FeatureMode.REQUIRED, FeatureSource.BGG_METADATA, "科幻主题");

        assertThat(coverage.uncoveredExpression("推荐4人、120分钟的科幻主题游戏", List.of(mapped)))
                .isBlank();
    }

    @Test
    void numericOnlyAndReplacementTurnsDoNotTriggerWebDiscovery() {
        assertThat(coverage.uncoveredExpression("推荐4人120分钟的游戏", List.of())).isBlank();
        assertThat(coverage.uncoveredExpression("换一批", List.of())).isBlank();
        assertThat(coverage.uncoveredExpression("再来几款", List.of())).isBlank();
    }
}
