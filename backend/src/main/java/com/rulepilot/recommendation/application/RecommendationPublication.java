package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic selection, evidence ownership, and player-facing projection for recommendation cards. */
final class RecommendationPublication {

    private final BoardGameRecommendationSelector selector;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions observations;
    private final RecommendationReActLoop runtime;

    RecommendationPublication(
            BoardGameRecommendationSelector selector,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions observations,
            RecommendationReActLoop runtime) {
        this.selector = selector;
        this.evidenceReview = evidenceReview;
        this.observations = observations;
        this.runtime = runtime;
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            Permit permit,
            String locale) {
        boolean chinese = runtime.chinese(locale);
        List<RecommendedGame> games = selector.present(
                permit.selectedGames(),
                state.profile,
                chinese);
        Set<String> publishedEvidenceIds = games.stream()
                .flatMap(game -> game.claims().stream())
                .flatMap(claim -> claim.evidence().stream())
                .map(CandidateObservation::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        String assistantMessage = recommendationMessage(permit, chinese);
        List<String> responseActions = new ArrayList<>(state.actions);
        if (permit.shortfall() != null) responseActions.add("RECOMMENDATION_VERIFIED_SET_SHORTFALL");
        responseActions.add("RECOMMEND_GAMES");
        List<BoardGameRecommendationAgent.ResearchSource> sources =
                runtime.responseSources(state, games, publishedEvidenceIds);
        ConversationResponse response = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                assistantMessage,
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                sources,
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        responseActions,
                        state.elapsedMs()),
                games,
                state.comparison,
                permit.shortfall());

        state.finalResponseGameIds.addAll(permit.selectedGames().stream()
                .map(game -> game.ranking().bggId())
                .toList());
        state.finalResponseEvidenceIds.addAll(publishedEvidenceIds);
        state.actions.clear();
        state.actions.addAll(responseActions);
        return response;
    }

    Permit permit(RecommendationAgentState state, PublicationSeed seed) {
        if (state == null) throw new IllegalArgumentException("recommendation state is required");
        if (seed == null) throw new IllegalArgumentException("publication seed is required");
        Set<Integer> currentRecommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        if (!currentRecommendable.containsAll(seed.candidateBggIds())) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        int requestedCount = Math.min(seed.requestedCount(), state.maximumRecommendationResults);
        List<Integer> selectedCandidateIds = seed.candidateBggIds().stream()
                .limit(requestedCount)
                .toList();

        List<Game> selectedGames = new ArrayList<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        for (Integer bggId : selectedCandidateIds) {
            if (!selectedIds.add(bggId)) throw invalid(Code.DUPLICATE_SELECTION);
            Game game = state.verified.get(bggId);
            if (game == null) throw invalid(Code.FINAL_ID_NOT_VERIFIED);
            if (state.excludedIds.contains(bggId)) throw invalid(Code.FINAL_ID_EXCLUDED);
            if (state.previouslyShownIds.contains(bggId) && !state.targetGameIds.contains(bggId)) {
                throw invalid(Code.FINAL_ID_PREVIOUSLY_SHOWN);
            }
            if (state.comparisonReferenceIds.contains(bggId)) {
                throw invalid(Code.FINAL_ID_IS_COMPARISON_REFERENCE);
            }
            if (!state.targetGameIds.contains(bggId) && !selector.eligible(game, state.profile)) {
                throw invalid(Code.FINAL_ID_FAILS_HARD_GATES);
            }
            if (observations.narrativeObservations(game, state.research).isEmpty()) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            }
            selectedGames.add(game);
        }

        List<Integer> referenceIds = seed.referenceBggIds().stream().limit(2).toList();
        if (referenceIds.stream().anyMatch(selectedIds::contains)) throw invalid(Code.REFERENCE_ID_SELECTED);
        for (Integer referenceId : referenceIds) {
            Game reference = state.verified.get(referenceId);
            if (reference == null || !state.comparisonReferenceIds.contains(referenceId)) {
                throw invalid(Code.REFERENCE_ID_NOT_VERIFIED);
            }
        }

        RecommendationShortfall shortfall = selectedGames.size() < requestedCount
                ? new RecommendationShortfall(requestedCount, selectedGames.size())
                : null;
        return new Permit(requestedCount, selectedGames, shortfall);
    }

    private String recommendationMessage(Permit permit, boolean chinese) {
        int available = permit.selectedGames().size();
        if (permit.shortfall() != null) {
            return chinese
                    ? "我核对了目录资料，目前有 " + available + " 款符合已确认的硬条件；没有用未核实条目凑满 "
                            + permit.requestedCount() + " 款。卡片里列出了匹配点和需要留意的边界。"
                    : "I checked the catalog evidence and found " + available
                            + " candidate(s) that satisfy the confirmed hard constraints; I did not pad the list to "
                            + permit.requestedCount()
                            + " with unverified entries. The cards show the verified matches and any open boundaries.";
        }
        return chinese
                ? "我核对了目录资料，先给你 " + available + " 款可以直接比较的候选。卡片里列出了匹配点和需要留意的边界。"
                : "I checked the catalog evidence and found " + available
                        + " candidate(s) ready to compare. The cards show the verified matches and any open boundaries.";
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    record Permit(
            int requestedCount,
            List<Game> selectedGames,
            RecommendationShortfall shortfall) {
        Permit {
            selectedGames = List.copyOf(selectedGames);
        }
    }

    enum Code {
        PUBLICATION_SEED_INVALID,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        REFERENCE_ID_SELECTED,
        REFERENCE_ID_NOT_VERIFIED
    }

    static final class InvalidPublication extends RuntimeException {
        private final Code code;

        private InvalidPublication(Code code) {
            super(code.name());
            this.code = code;
        }

        Code code() {
            return code;
        }
    }
}
