package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionCriterion;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionDimension;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionIntent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationSelectorTest {

    private final BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(
            new BoardGameRecommendationProperties(8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20)));

    @Test
    void acceptsTheMeasuredThirtySecondRunBudgetAndRejectsAnythingLonger() {
        var accepted = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));

        assertThat(accepted.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> new BoardGameRecommendationProperties(
                        8, 3, new BigDecimal("0.66"), Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer than 30 seconds");
    }

    @Test
    void preservesAgentSelectionOrderWithoutGeneratingApplicationRecommendationProse() {
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
        assertThat(result).allSatisfy(game -> {
            assertThat(game.matches()).isEmpty();
            assertThat(game.tradeoffs()).isEmpty();
            assertThat(game.reasons()).isEmpty();
        });
    }

    @Test
    void retainsVerifiedTaxonomyAsRawCandidateObservationsWithoutInventingExperienceProse() {
        Game candidate = game(
                1,
                100,
                new BigDecimal("3.0"),
                List.of("Hand Management", "Network and Route Building"));
        RecommendationProfile profile = new RecommendationProfile(
                4, 120, null, BggGameType.ALL, InteractionPreference.ANY);

        var observations = selector.observations(candidate);

        assertThat(observations)
                .filteredOn(observation -> observation.attribute().equals("mechanics"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.kind())
                            .isEqualTo(com.rulepilot.recommendation.CandidateObservation.Kind.TAXONOMY);
                    assertThat(observation.value()).isEqualTo("Hand Management, Network and Route Building");
                });
        assertThat(selector.present(List.of(candidate), profile, List.of(), true, Research.empty()).getFirst())
                .satisfies(result -> {
                    assertThat(result.reasons()).isEmpty();
                    assertThat(result.tradeoffs()).isEmpty();
                });
    }

    @Test
    void exposesTheCompletePublisherDescriptionAsCandidateScopedMetadata() {
        String description = "Build a floating archive where players preserve memories.   "
                + "Each round introduces a new island. ".repeat(80);

        var observations = selector.observations(gameWithDescription(41, description));

        assertThat(observations)
                .filteredOn(observation -> observation.attribute().equals("publisherDescription"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.id()).isEqualTo("B41:publisherDescription");
                    assertThat(observation.kind())
                            .isEqualTo(com.rulepilot.recommendation.CandidateObservation.Kind.STRUCTURED_METADATA);
                    assertThat(observation.value()).isEqualTo(description.strip());
                });
    }

    @Test
    void omitsAnAbsentPublisherDescriptionInsteadOfInventingOne() {
        assertThat(selector.observations(game(42, 60, new BigDecimal("2.2"), List.of("Drafting"))))
                .noneMatch(observation -> observation.attribute().equals("publisherDescription"));
    }

    @Test
    void appliesOnlyStructuredNumericFactsAsHardGatesWithoutParsingTaxonomyLabels() {
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
                .isTrue();
    }

    @Test
    void keepsTwoSidedHardRangesIntactAcrossEligibilityAndPlayerFacingReasons() {
        RecommendationProfile profile = new RecommendationProfile(
                ConstraintRange.hard(3, 4, "3–4 players", 1),
                ConstraintRange.hard(120, 180, "120–180 minutes", 1),
                ConstraintRange.hard(new BigDecimal("2.0"), new BigDecimal("3.0"), "weight 2–3", 1),
                BggGameType.ALL,
                InteractionPreference.ANY);

        Game exactFit = gameWithRanges(10, 2, 5, 120, 180, new BigDecimal("2.5"));
        Game entirelyTooShort = gameWithRanges(11, 2, 5, 60, 90, new BigDecimal("2.5"));
        Game onlyPartlyOverlaps = gameWithRanges(12, 2, 5, 100, 150, new BigDecimal("2.5"));
        Game missesOnePlayerCount = gameWithRanges(13, 2, 3, 120, 180, new BigDecimal("2.5"));
        Game tooLight = gameWithRanges(14, 2, 5, 120, 180, new BigDecimal("1.5"));

        assertThat(selector.eligible(exactFit, profile)).isTrue();
        assertThat(selector.eligible(entirelyTooShort, profile)).isFalse();
        assertThat(selector.eligible(onlyPartlyOverlaps, profile))
                .as("a partially overlapping advertised duration is not a proven hard-range match")
                .isFalse();
        assertThat(selector.eligible(missesOnePlayerCount, profile)).isFalse();
        assertThat(selector.eligible(tooLight, profile)).isFalse();

        var presented = selector.present(
                List.of(exactFit), profile, List.of(), true, Research.empty()).getFirst();
        assertThat(presented.matches()).isEmpty();
        assertThat(presented.reasons()).isEmpty();
        assertThat(presented.claims())
                .filteredOn(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                .extracting(CandidateClaim::relation)
                .containsExactly(
                        CandidateClaim.Relation.SATISFIED,
                        CandidateClaim.Relation.SATISFIED,
                        CandidateClaim.Relation.SATISFIED);
        assertThat(presented.claims()).allSatisfy(claim -> claim.evidence().forEach(observation ->
                assertThat(observation.bggId()).isEqualTo(10)));
    }

    @Test
    void usesTheSameTypedAssessmentForSoftPresentationWithoutTurningItIntoAHardGate() {
        RecommendationProfile profile = new RecommendationProfile(
                null,
                new ConstraintRange<>(
                        120,
                        180,
                        ConstraintRange.Strength.SOFT,
                        "ideally 120–180 minutes",
                        2),
                null,
                BggGameType.ALL,
                InteractionPreference.ANY);
        Game shortCandidate = gameWithRanges(19, 2, 5, 45, 75, new BigDecimal("2.2"));

        assertThat(selector.eligible(shortCandidate, profile))
                .as("a soft preference is reported honestly but does not exclude the candidate")
                .isTrue();
        var presented = selector.present(
                        List.of(shortCandidate), profile, List.of(), false, Research.empty())
                .getFirst();
        assertThat(presented.matches()).isEmpty();
        assertThat(presented.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.subject()).isEqualTo("durationMinutes");
            assertThat(claim.strength()).isEqualTo(ConstraintRange.Strength.SOFT);
            assertThat(claim.relation()).isEqualTo(CandidateClaim.Relation.CONFLICT);
            assertThat(claim.text())
                    .contains("preferred range")
                    .doesNotContain("hard constraint");
        });
    }

    @Test
    void distinguishesConflictUnknownAndSatisfiedFromCandidateSpecificFacts() {
        RecommendationProfile profile = new RecommendationProfile(
                ConstraintRange.hard(3, 4, "3–4 players", 1),
                ConstraintRange.hard(120, 180, "120–180 minutes", 1),
                null,
                BggGameType.ALL,
                InteractionPreference.ANY);
        Game shortCandidate = gameWithRanges(21, 2, 5, 45, 75, new BigDecimal("2.2"));
        Game partialCandidate = gameWithRanges(22, 2, 5, 90, 150, new BigDecimal("2.2"));

        assertThat(selector.fitClaims(shortCandidate, profile, false))
                .filteredOn(claim -> claim.text().contains("duration"))
                .singleElement()
                .extracting(CandidateClaim::relation)
                .isEqualTo(CandidateClaim.Relation.CONFLICT);
        assertThat(selector.fitClaims(partialCandidate, profile, false))
                .filteredOn(claim -> claim.text().contains("duration"))
                .singleElement()
                .extracting(CandidateClaim::relation)
                .isEqualTo(CandidateClaim.Relation.UNKNOWN);
        assertThat(selector.fitClaims(partialCandidate, profile, false))
                .filteredOn(claim -> claim.text().contains("player"))
                .singleElement()
                .extracting(CandidateClaim::relation)
                .isEqualTo(CandidateClaim.Relation.SATISFIED);
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

    @Test
    void appliesEverySupportedCatalogDimensionAsTheSameExactCandidateGate() {
        Game candidate = new Game(
                new Ranking(
                        71,
                        "Guild Archive",
                        2025,
                        71,
                        new BigDecimal("7.2"),
                        new BigDecimal("7.5"),
                        2_000,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Guild Archive",
                        "行会档案",
                        "",
                        2,
                        4,
                        120,
                        new BigDecimal("3.5"),
                        List.of("Economic"),
                        List.of("Deck, Bag, and Pool Building"),
                        90,
                        120,
                        12,
                        12,
                        "3",
                        "2-4",
                        2,
                        100,
                        List.of("Cities: Hanseatic"),
                        List.of("Avery Stone"),
                        List.of("Open Shelf"),
                        "Build a trading archive.",
                        ""));
        List<CatalogSelectionCriterion> criteria = List.of(
                criterion(CatalogSelectionDimension.CATEGORY, "Economic"),
                criterion(CatalogSelectionDimension.MECHANIC, "Deck, Bag, and Pool Building"),
                criterion(CatalogSelectionDimension.FAMILY, "Cities: Hanseatic"),
                criterion(CatalogSelectionDimension.DESIGNER, "Avery Stone"),
                criterion(CatalogSelectionDimension.PUBLISHER, "Open Shelf"));

        assertThat(selector.eligible(
                        candidate,
                        RecommendationProfile.empty(),
                        new CatalogSelectionIntent(criteria)))
                .isTrue();
        assertThat(selector.fitClaims(
                        candidate,
                        RecommendationProfile.empty(),
                        new CatalogSelectionIntent(criteria),
                        false))
                .filteredOn(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                .extracting(CandidateClaim::subject, CandidateClaim::relation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("categories", CandidateClaim.Relation.SATISFIED),
                        org.assertj.core.groups.Tuple.tuple("mechanics", CandidateClaim.Relation.SATISFIED),
                        org.assertj.core.groups.Tuple.tuple("families", CandidateClaim.Relation.SATISFIED),
                        org.assertj.core.groups.Tuple.tuple("designers", CandidateClaim.Relation.SATISFIED),
                        org.assertj.core.groups.Tuple.tuple("publishers", CandidateClaim.Relation.SATISFIED));
        for (CatalogSelectionDimension dimension : CatalogSelectionDimension.values()) {
            assertThat(selector.eligible(
                            candidate,
                            RecommendationProfile.empty(),
                            new CatalogSelectionIntent(List.of(criterion(dimension, "Different canonical value")))))
                    .as("a mismatch in %s must reject the candidate", dimension)
                    .isFalse();
        }
    }

    private CatalogSelectionCriterion criterion(CatalogSelectionDimension dimension, String value) {
        return new CatalogSelectionCriterion(dimension, value, "player-authored direction", 1);
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

    private Game gameWithDescription(int id, String description) {
        return new Game(
                new Ranking(
                        id,
                        "Game " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Game " + id,
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.2"),
                        List.of("Strategy"),
                        List.of("Open Drafting"),
                        45,
                        60,
                        10,
                        10,
                        "3",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of(),
                        description,
                        ""));
    }

    private Game gameWithRanges(
            int id,
            int minimumPlayers,
            int maximumPlayers,
            int minimumMinutes,
            int maximumMinutes,
            BigDecimal weight) {
        return new Game(
                new Ranking(
                        id,
                        "Game " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Game " + id,
                        "",
                        "",
                        minimumPlayers,
                        maximumPlayers,
                        maximumMinutes,
                        weight,
                        List.of("Strategy"),
                        List.of("Open Drafting"),
                        minimumMinutes,
                        maximumMinutes,
                        10,
                        10,
                        Integer.toString(maximumPlayers),
                        minimumPlayers + "-" + maximumPlayers,
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
