package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
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
    void acceptsTheMeasuredFortyFiveSecondRunBudgetAndRejectsAnythingLonger() {
        var accepted = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(45));

        assertThat(accepted.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThatThrownBy(() -> new BoardGameRecommendationProperties(
                        8, 3, new BigDecimal("0.66"), Duration.ofSeconds(46)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer than 45 seconds");
    }

    @Test
    void preservesAgentSelectionOrderWhenNoProfileClaimsAreAvailable() {
        Game second = game(2, 50, new BigDecimal("2.2"), List.of("Open Drafting"));
        Game first = game(1, 45, new BigDecimal("2.0"), List.of("Pattern Building"));

        var result = selector.present(
                List.of(second, first),
                RecommendationProfile.empty(),
                true);

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
                ConstraintRange.hardExact(4),
                ConstraintRange.hardAtMost(120),
                null,
                BggGameType.ALL,
                InteractionPreference.ANY);

        var observations = selector.observations(candidate);

        assertThat(observations)
                .filteredOn(observation -> observation.attribute().equals("mechanics"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.kind())
                            .isEqualTo(com.rulepilot.recommendation.CandidateObservation.Kind.TAXONOMY);
                    assertThat(observation.value()).isEqualTo("Hand Management, Network and Route Building");
                });
        assertThat(selector.present(List.of(candidate), profile, true).getFirst())
                .satisfies(result -> {
                    assertThat(result.matches()).hasSize(2);
                    assertThat(result.reasons()).hasSize(2);
                    assertThat(result.tradeoffs()).isEmpty();
                    assertThat(result.replyParts())
                            .as("verified selector claims are facts, not model-authored card prose")
                            .isEmpty();
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
                ConstraintRange.hardExact(4),
                ConstraintRange.hardAtMost(60),
                ConstraintRange.hardAtMost(new BigDecimal("2.5")),
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

        var presented = selector.present(List.of(exactFit), profile, true).getFirst();
        assertThat(presented.matches()).hasSize(3);
        assertThat(presented.tradeoffs()).isEmpty();
        assertThat(presented.reasons()).hasSize(3);
        assertThat(presented.replyParts())
                .as("only the terminal recommend action may author player-facing card notes")
                .isEmpty();
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
        var presented = selector.present(List.of(shortCandidate), profile, false).getFirst();
        assertThat(presented.matches()).isEmpty();
        assertThat(presented.tradeoffs()).singleElement().isEqualTo(presented.claims().getFirst().text());
        assertThat(presented.reasons()).hasSize(1);
        assertThat(presented.replyParts())
                .as("a deterministic constraint conflict remains a claim, not a drafted tradeoff")
                .isEmpty();
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
                ConstraintRange.hardExact(2),
                null,
                null,
                BggGameType.PARTY,
                InteractionPreference.ANY);

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
                ConstraintRange.hardExact(4),
                ConstraintRange.hardAtMost(60),
                null,
                BggGameType.ALL,
                InteractionPreference.ANY);
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
