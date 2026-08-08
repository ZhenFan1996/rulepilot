package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
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

    @Test
    void doesNotTurnAlreadyStructuredProfileLanguageIntoAnExternalEvidenceGap() {
        var structured = new RecommendationProfile(
                4,
                60,
                new BigDecimal("2.3"),
                BggGameType.STRATEGY,
                InteractionPreference.COOPERATIVE);

        assertThat(coverage.uncoveredExpression(
                        "推荐4人、60分钟、简单的合作策略游戏", List.of(), structured))
                .isBlank();
        assertThat(coverage.uncoveredExpression(
                        "4 players, 60 minutes, easy to learn cooperative strategy game", List.of(), structured))
                .isBlank();
    }

    @Test
    void preservesQualitativeRemaindersAndDoesNotStripUnstructuredInteractionWords() {
        var cooperative = new RecommendationProfile(
                null, null, null, BggGameType.ALL, InteractionPreference.COOPERATIVE);

        assertThat(coverage.uncoveredExpression("合作但希望沟通压力低", List.of(), cooperative))
                .isEqualTo("但 沟通压力低");
        assertThat(coverage.uncoveredExpression(
                        "合作游戏", List.of(), RecommendationProfile.empty()))
                .isEqualTo("合作");
    }
}
