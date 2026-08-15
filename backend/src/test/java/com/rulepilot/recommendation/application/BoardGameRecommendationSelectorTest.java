package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationSelectorTest {

    private final BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(
            new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20)));

    @Test
    void rejectsAWholeTurnBudgetLongerThanThePlayerFacingTwentySecondContract() {
        assertThatThrownBy(() -> new BoardGameRecommendationProperties(
                        8, 3, new BigDecimal("0.66"), Duration.ofSeconds(21)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer than 20 seconds");
    }

    @Test
    void preservesAgentSelectionOrderAndDerivesSharedTaxonomyFromVerifiedGames() {
        Game second = game(2, 50, new BigDecimal("2.2"), List.of("Open Drafting"));
        Game first = game(1, 45, new BigDecimal("2.0"), List.of("Pattern Building"));
        Game reference = game(3, 55, new BigDecimal("2.1"), List.of("Open Drafting", "Tile Placement"));

        var result = selector.present(
                List.of(second, first),
                RecommendationProfile.empty(),
                List.of(reference),
                true,
                Research.empty());

        assertThat(result).extracting(value -> value.game().ranking().bggId()).containsExactly(2, 1);
        assertThat(result.getFirst().matches()).singleElement().asString()
                .contains("与参考游戏共有的 BGG 机制/类型", "Open Drafting", "Abstract Strategy");
        assertThat(result.get(1).matches()).singleElement().asString()
                .contains("Abstract Strategy")
                .doesNotContain("Pattern Building");
    }

    @Test
    void showsVerifiedTaxonomyAndAnHonestBoundaryWhenNoExperienceResearchExists() {
        Game candidate = game(
                1,
                100,
                new BigDecimal("3.0"),
                List.of("Hand Management", "Network and Route Building"));
        RecommendationProfile profile = new RecommendationProfile(
                4, 120, null, BggGameType.ALL, InteractionPreference.ANY);

        var result = selector.present(
                List.of(candidate),
                profile,
                List.of(),
                true,
                Research.empty()).getFirst();

        assertThat(result.reasons())
                .filteredOn(reason -> reason.kind() == ReasonKind.BGG_FACT)
                .extracting(reason -> reason.text())
                .anySatisfy(text -> assertThat(text)
                        .contains("BGG 机制/类型标签", "Hand Management", "Network and Route Building"));
        assertThat(result.tradeoffs()).singleElement().asString()
                .contains("BGG 标签", "不能证明", "互动感", "等待时间");
    }

    @Test
    void keepsEachPreferenceInferenceAttachedToItsOwnQuoteAndCandidateTaxonomy() {
        Game pattern = game(1, 50, new BigDecimal("2.2"), List.of("Pattern Building"));
        Game drafting = game(2, 55, new BigDecimal("2.4"), List.of("Open Drafting"));

        var result = selector.present(
                List.of(pattern, drafting),
                RecommendationProfile.empty(),
                List.of(),
                true,
                Research.empty(),
                Map.of(
                        1,
                        new BoardGameRecommendationSelector.PreferenceLink(
                                "想拼出自己的版图", List.of("Pattern Building")),
                        2,
                        new BoardGameRecommendationSelector.PreferenceLink(
                                "喜欢公开选牌的拉扯", List.of("Open Drafting"))));

        assertThat(result.get(0).reasons())
                .filteredOn(reason -> reason.kind() == ReasonKind.PREFERENCE_INFERENCE)
                .singleElement()
                .extracting(reason -> reason.text())
                .asString()
                .contains("想拼出自己的版图", "Pattern Building")
                .doesNotContain("公开选牌", "Open Drafting");
        assertThat(result.get(1).reasons())
                .filteredOn(reason -> reason.kind() == ReasonKind.PREFERENCE_INFERENCE)
                .singleElement()
                .extracting(reason -> reason.text())
                .asString()
                .contains("喜欢公开选牌的拉扯", "Open Drafting")
                .doesNotContain("自己的版图", "Pattern Building");
    }

    @Test
    void appliesPlayerDurationWeightAndInteractionAsApplicationOwnedHardGates() {
        RecommendationProfile profile = new RecommendationProfile(
                4,
                60,
                new BigDecimal("2.5"),
                BggGameType.ALL,
                InteractionPreference.COMPETITIVE);

        assertThat(selector.eligible(
                        game(1, 60, new BigDecimal("2.5"), List.of("Pattern Building")),
                        profile))
                .isTrue();
        assertThat(selector.eligible(
                        game(2, 90, new BigDecimal("2.5"), List.of("Pattern Building")),
                        profile))
                .isFalse();
        assertThat(selector.eligible(
                        game(3, 60, new BigDecimal("3.0"), List.of("Pattern Building")),
                        profile))
                .isFalse();
        assertThat(selector.eligible(
                        game(4, 60, new BigDecimal("2.0"), List.of("Cooperative Game")),
                        profile))
                .isFalse();
    }

    @Test
    void appliesTheRequestedBggRankingTypeToEveryCandidateRegardlessOfItsDiscoveryPath() {
        RecommendationProfile partyProfile = new RecommendationProfile(
                2, null, null, BggGameType.PARTY, InteractionPreference.ANY);

        assertThat(selector.eligible(
                        game(1, 45, new BigDecimal("1.5"), List.of("Voting"), BggGameType.PARTY),
                        partyProfile))
                .isTrue();
        assertThat(selector.eligible(
                        game(2, 45, new BigDecimal("2.0"), List.of("Pattern Building"), BggGameType.ABSTRACT),
                        partyProfile))
                .isFalse();
        assertThat(selector.eligible(
                        game(3, 45, new BigDecimal("2.0"), List.of("Set Collection"), null),
                        partyProfile))
                .as("unknown ranking type cannot satisfy an explicit type request")
                .isFalse();
    }

    @Test
    void broadBrowseFiltersExcludedAndIneligibleGamesWithoutReorderingTheRemainingPool() {
        RecommendationProfile profile = new RecommendationProfile(
                4, 60, null, BggGameType.ALL, InteractionPreference.ANY);
        List<Game> result = selector.eligible(
                List.of(
                        game(1, 45, new BigDecimal("2"), List.of("Pattern Building")),
                        game(2, 120, new BigDecimal("2"), List.of("Trading")),
                        game(3, 50, new BigDecimal("2"), List.of("Open Drafting"))),
                profile,
                Set.of(3),
                8);

        assertThat(result).extracting(value -> value.ranking().bggId()).containsExactly(1);
    }

    private Game game(int id, int maximumMinutes, BigDecimal weight, List<String> mechanics) {
        return game(id, maximumMinutes, weight, mechanics, BggGameType.ABSTRACT);
    }

    private Game game(
            int id,
            int maximumMinutes,
            BigDecimal weight,
            List<String> mechanics,
            BggGameType type) {
        return new Game(
                new Ranking(
                        id,
                        "Game " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        type == null ? List.of() : List.of(type)),
                new Details(
                        "Game " + id,
                        "",
                        "",
                        2,
                        4,
                        maximumMinutes,
                        weight,
                        List.of("Abstract Strategy"),
                        mechanics,
                        Math.max(15, maximumMinutes - 10),
                        maximumMinutes,
                        10,
                        10,
                        "4",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
