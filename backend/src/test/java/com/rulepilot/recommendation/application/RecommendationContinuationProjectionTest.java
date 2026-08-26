package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ContinuationAvailability;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationContinuationProjectionTest {

    @Test
    void publishesAnExactReadyCardWhileLeavingAnotherCandidatesAvailabilityUncertain() {
        RecommendationAgentState state = state();
        state.recordSuccessfulTeachingContinuationLookup(
                List.of(22),
                Map.of(22, new Continuation(22, UUID.randomUUID(), 3, 9)));
        RecommendedGame ready = new RecommendedGame(
                game(22),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                RecommendationContinuationProjection.card(state.teachingContinuations.get(22)));
        RecommendedGame unqueried = new RecommendedGame(game(21), List.of(), List.of());

        assertThat(RecommendationContinuationProjection.response(state, List.of(ready, unqueried)).availability())
                .isEqualTo(ContinuationAvailability.AVAILABLE_FOR_SOME);
        assertThat(state.teachingContinuations).containsOnlyKeys(22);
    }

    @Test
    void reportsAvailabilityUnavailableWhenNoReadyCardExistsAndAFinalCandidateWasNotResolved() {
        RecommendationAgentState state = state();

        assertThat(RecommendationContinuationProjection.response(
                                state,
                                List.of(new RecommendedGame(game(21), List.of(), List.of())))
                        .availability())
                .isEqualTo(ContinuationAvailability.AVAILABILITY_UNAVAILABLE);
    }

    @Test
    void reportsNoReadyCandidateOnlyAfterTheExactFinalCandidateWasQueried() {
        RecommendationAgentState state = state();
        state.recordSuccessfulTeachingContinuationLookup(List.of(21), Map.of());
        RecommendedGame game = new RecommendedGame(game(21), List.of(), List.of());

        assertThat(RecommendationContinuationProjection.response(state, List.of(game)).availability())
                .isEqualTo(ContinuationAvailability.NO_READY_CANDIDATE);
    }

    @Test
    void aPartialLookupResolvesOnlyItsExactPositiveMatches() {
        RecommendationAgentState state = state();

        boolean ready = state.recordPartialTeachingContinuationLookup(
                List.of(21, 22),
                Map.of(22, new Continuation(22, UUID.randomUUID(), 3, 9)));

        assertThat(ready).isTrue();
        assertThat(state.teachingContinuationQueriedIds).containsExactly(22);
        assertThat(state.teachingContinuationUnavailableIds).containsExactly(21);
        assertThat(state.teachingContinuations).containsOnlyKeys(22);
    }

    @Test
    void ignoresAContinuationReturnedOutsideTheExactLookupScope() {
        RecommendationAgentState state = state();

        boolean ready = state.recordSuccessfulTeachingContinuationLookup(
                List.of(21),
                Map.of(22, new Continuation(22, UUID.randomUUID(), 3, 9)));

        assertThat(ready).isFalse();
        assertThat(state.teachingContinuations).isEmpty();
        assertThat(state.teachingContinuationQueriedIds).containsExactly(21);
    }

    private RecommendationAgentState state() {
        RecommendationAgentState state = new RecommendationAgentState(
                new ConversationRequest(RecommendationProfile.empty(), "recommend one"),
                System.nanoTime(),
                "player",
                false,
                8);
        state.teachingContinuationRequested = true;
        return state;
    }

    private Game game(int bggId) {
        return new Game(
                new Ranking(
                        bggId,
                        "Synthetic " + bggId,
                        2025,
                        bggId,
                        new BigDecimal("8.0"),
                        new BigDecimal("8.1"),
                        1_000),
                new Details(
                        "Synthetic " + bggId,
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.5"),
                        List.of(),
                        List.of(),
                        45,
                        60,
                        10,
                        10,
                        "4",
                        "3-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
