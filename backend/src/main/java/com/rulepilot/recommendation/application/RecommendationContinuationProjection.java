package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ContinuationAvailability;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ContinuationKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationContinuation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TeachingContinuation;
import java.util.List;

/** Deterministic response projection for the typed recommendation-to-teaching handoff. */
final class RecommendationContinuationProjection {

    private RecommendationContinuationProjection() {}

    static TeachingContinuation card(Continuation value) {
        return value == null
                ? null
                : new TeachingContinuation(
                        value.teachingPlanId(),
                        value.sectionCount(),
                        value.stepCount());
    }

    static RecommendationContinuation response(
            RecommendationAgentState state,
            List<RecommendedGame> games) {
        if (!state.teachingContinuationRequested || games.isEmpty()) return null;
        int readyCount = (int) games.stream()
                .filter(game -> game.teachingContinuation() != null)
                .count();
        boolean availabilityKnownForEveryCandidate = games.stream()
                .map(game -> game.game().ranking().bggId())
                .allMatch(bggId -> state.teachingContinuationQueriedIds.contains(bggId)
                        && !state.teachingContinuationUnavailableIds.contains(bggId));
        ContinuationAvailability availability;
        if (readyCount == games.size()) {
            availability = ContinuationAvailability.AVAILABLE_FOR_ALL;
        } else if (readyCount > 0) {
            availability = ContinuationAvailability.AVAILABLE_FOR_SOME;
        } else if (!availabilityKnownForEveryCandidate) {
            availability = ContinuationAvailability.AVAILABILITY_UNAVAILABLE;
        } else {
            availability = ContinuationAvailability.NO_READY_CANDIDATE;
        }
        return new RecommendationContinuation(
                ContinuationKind.GUIDE_AND_RULE_QA,
                state.teachingLearningGoal,
                availability,
                readyCount,
                games.size());
    }
}
