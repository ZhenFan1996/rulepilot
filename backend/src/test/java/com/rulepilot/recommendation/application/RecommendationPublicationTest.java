package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationPublicationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesTheVerifiedSeedInOrderAndReportsAnHonestShortfall() {
        Fixture fixture = fixture(
                List.of(101, 102),
                new RecommendationProfile(
                        ConstraintRange.hardExact(3),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
                "三个人，六十分钟以内");
        PublicationSeed seed = new PublicationSeed(List.of(102, 101), List.of(), 3);

        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        var response = fixture.publication.publish(fixture.state, permit, "zh-CN");

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games())
                .extracting(game -> game.game().ranking().bggId())
                .containsExactly(102, 101);
        assertThat(response.shortfall()).satisfies(shortfall -> {
            assertThat(shortfall.requestedCount()).isEqualTo(3);
            assertThat(shortfall.availableCount()).isEqualTo(2);
        });
        assertThat(response.assistantMessage())
                .contains("目前有 2 款符合已确认的硬条件", "没有用未核实条目凑满 3 款");
        assertThat(response.games()).allSatisfy(game -> {
            assertThat(game.matches()).hasSize(2);
            assertThat(game.tradeoffs()).isEmpty();
            assertThat(game.reasons()).hasSize(2);
            assertThat(game.claims()).hasSize(2);
            assertThat(game.replyParts())
                    .extracting(BoardGameRecommendationAgent.RecommendationReplyPart::role)
                    .containsOnly(ReplyPartRole.WHY_FIT);
            assertThat(game.claims()).allSatisfy(claim -> {
                assertThat(claim.type()).isEqualTo(CandidateClaim.Type.CONSTRAINT_FIT);
                assertThat(claim.relation()).isEqualTo(CandidateClaim.Relation.SATISFIED);
                assertThat(claim.evidence())
                        .isNotEmpty()
                        .extracting(CandidateObservation::bggId)
                        .containsOnly(game.game().ranking().bggId());
            });
        });
        assertThat(fixture.state.finalResponseGameIds).containsExactlyInAnyOrder(101, 102);
        assertThat(fixture.state.finalResponseEvidenceIds).isNotEmpty();
        assertThat(response.harness().actions())
                .containsExactly("RECOMMENDATION_VERIFIED_SET_SHORTFALL", "RECOMMEND_GAMES");
    }

    @Test
    void projectsASoftConflictAsATradeoffWithoutTurningItIntoAHardGate() {
        RecommendationProfile profile = new RecommendationProfile(
                ConstraintRange.hardExact(3),
                ConstraintRange.hardAtMost(60),
                new ConstraintRange<>(
                        null,
                        new BigDecimal("2.0"),
                        ConstraintRange.Strength.SOFT,
                        "prefer lighter games",
                        1),
                BggGameType.ALL,
                InteractionPreference.ANY);
        Fixture fixture = fixture(List.of(101), profile, "Three players, one hour, preferably light.");
        PublicationSeed seed = new PublicationSeed(List.of(101), List.of(), 1);

        RecommendationPublication.Permit permit = fixture.publication.permit(fixture.state, seed);
        var response = fixture.publication.publish(fixture.state, permit, "en");

        assertThat(response.shortfall()).isNull();
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.matches()).hasSize(2);
            assertThat(game.tradeoffs())
                    .singleElement()
                    .satisfies(tradeoff -> assertThat(tradeoff).contains("preferred range"));
            assertThat(game.reasons()).hasSize(3);
            assertThat(game.claims())
                    .extracting(CandidateClaim::relation)
                    .containsExactly(
                            CandidateClaim.Relation.SATISFIED,
                            CandidateClaim.Relation.SATISFIED,
                            CandidateClaim.Relation.CONFLICT);
            assertThat(game.replyParts())
                    .extracting(BoardGameRecommendationAgent.RecommendationReplyPart::role)
                    .containsExactly(
                            ReplyPartRole.WHY_FIT,
                            ReplyPartRole.WHY_FIT,
                            ReplyPartRole.TRADEOFF);
        });
        assertThat(response.assistantMessage()).contains("1 candidate(s) ready to compare");
        assertThat(response.harness().actions()).containsExactly("RECOMMEND_GAMES");
    }

    @Test
    void rejectsAnUnverifiedOrHardIneligibleSeedBeforePublication() {
        Fixture fixture = fixture(List.of(101));
        when(fixture.runtime.recommendableIds(fixture.state)).thenReturn(List.of(101, 999));

        assertFailure(
                () -> fixture.publication.permit(
                        fixture.state,
                        new PublicationSeed(List.of(999), List.of(), 1)),
                RecommendationPublication.Code.FINAL_ID_NOT_VERIFIED);

        Fixture ineligible = fixture(
                List.of(101),
                new RecommendationProfile(
                        ConstraintRange.hardExact(5),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
                "五个人，一小时内");
        when(ineligible.runtime.recommendableIds(ineligible.state)).thenReturn(List.of(101));
        assertFailure(
                () -> ineligible.publication.permit(
                        ineligible.state,
                        new PublicationSeed(List.of(101), List.of(), 1)),
                RecommendationPublication.Code.FINAL_ID_FAILS_HARD_GATES);
        assertThat(ineligible.state.finalResponseGameIds).isEmpty();
        assertThat(ineligible.state.finalResponseEvidenceIds).isEmpty();
    }

    private Fixture fixture(List<Integer> verifiedIds) {
        return fixture(
                verifiedIds,
                new RecommendationProfile(
                        ConstraintRange.hardExact(3),
                        ConstraintRange.hardAtMost(60),
                        null,
                        BggGameType.ALL,
                        InteractionPreference.ANY),
                "三个人，六十分钟以内");
    }

    private Fixture fixture(
            List<Integer> verifiedIds,
            RecommendationProfile profile,
            String message) {
        RecommendationAgentState state = new RecommendationAgentState(
                new ConversationRequest(profile, message),
                System.nanoTime(),
                null,
                false,
                3);
        verifiedIds.stream().map(this::game).forEach(state::addVerified);
        BoardGameRecommendationSelector selector = new BoardGameRecommendationSelector(properties());
        RecommendationReActLoop runtime = mock(RecommendationReActLoop.class);
        when(runtime.recommendableIds(state)).thenReturn(verifiedIds);
        when(runtime.chinese("zh-CN")).thenReturn(true);
        when(runtime.chinese("en")).thenReturn(false);
        when(runtime.responseSources(eq(state), anyList(), anySet())).thenReturn(List.of());
        RecommendationEvidenceReview review = new RecommendationEvidenceReview(json, runtime);
        RecommendationActions observations = new RecommendationActions(
                null, selector, properties(), json, review, runtime);
        RecommendationPublication publication = new RecommendationPublication(
                selector, review, observations, runtime);
        return new Fixture(state, runtime, publication);
    }

    private void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            RecommendationPublication.Code expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(
                RecommendationPublication.InvalidPublication.class,
                failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private BoardGameRecommendationProperties properties() {
        return new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Signal Grove " + id,
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

    private record Fixture(
            RecommendationAgentState state,
            RecommendationReActLoop runtime,
            RecommendationPublication publication) {}
}
