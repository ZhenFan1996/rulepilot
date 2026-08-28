package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReplyPart;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateReplyDraft;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationDraft;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationAgentState.RecommendationReplyDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates candidate/evidence ownership and projects model-authored recommendation prose unchanged. */
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
            PublicationDraft draft,
            String locale) {
        Map<Integer, CandidateReplyDraft> draftsById = draft.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateReplyDraft::bggId,
                        candidate -> candidate,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<RecommendedGame> games = selector.present(
                        permit.selectedGames(),
                        state.profile,
                        runtime.chinese(locale))
                .stream()
                .map(game -> projectModelReply(
                        game,
                        draftsById.get(game.game().ranking().bggId()),
                        state))
                .toList();
        return response(
                state,
                permit,
                games,
                draft.playerReply(),
                draft.playerReplyEvidenceIds(),
                locale,
                false,
                List.of());
    }

    ConversationResponse publishFallback(
            RecommendationAgentState state,
            Permit permit,
            String locale,
            String failureCode) {
        List<RecommendedGame> games = selector.present(
                permit.selectedGames(),
                state.profile,
                runtime.chinese(locale));
        String message = runtime.chinese(locale)
                ? "候选卡片已经通过资料核验，但这轮的自然语言说明没有完成。为避免编造，我保留已核验的卡片事实；你可以直接重试生成完整说明。"
                : "The candidate cards passed evidence checks, but the natural-language explanation did not finish. To avoid inventing details, I kept the verified card facts; you can retry the complete explanation.";
        return response(
                state,
                permit,
                games,
                message,
                List.of(),
                locale,
                true,
                List.of("RECOMMENDATION_REPLY_FALLBACK:" + failureCode));
    }

    private ConversationResponse response(
            RecommendationAgentState state,
            Permit permit,
            List<RecommendedGame> games,
            String assistantMessage,
            List<String> playerReplyEvidenceIds,
            String locale,
            boolean fallbackUsed,
            List<String> additionalActions) {
        Set<String> publishedEvidenceIds = new LinkedHashSet<>(playerReplyEvidenceIds);
        games.stream()
                .flatMap(game -> java.util.stream.Stream.concat(
                        game.claims().stream(),
                        game.replyParts().stream().map(RecommendationReplyPart::claim)))
                .flatMap(claim -> claim.evidence().stream())
                .map(CandidateObservation::id)
                .forEach(publishedEvidenceIds::add);

        List<String> responseActions = new ArrayList<>(state.actions);
        additionalActions.stream()
                .filter(action -> !responseActions.contains(action))
                .forEach(responseActions::add);
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
                        fallbackUsed,
                        responseActions,
                        state.elapsedMs(),
                        state.modelCallElapsedMs),
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

    Permit permit(RecommendationAgentState state, PublicationDraft draft) {
        if (draft == null) throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        if (!withinCodePointBounds(
                draft.playerReply(),
                RecommendationAgentState.MIN_RECOMMENDATION_REPLY_CODE_POINTS,
                RecommendationAgentState.MAX_RECOMMENDATION_REPLY_CODE_POINTS)) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        }
        PublicationSeed pending = state == null ? null : state.pendingPublicationSeed;
        if (pending == null) throw invalid(Code.PUBLICATION_STATE_MISSING);
        Set<Integer> recommendableIds = new LinkedHashSet<>(runtime.recommendableIds(state));
        List<Integer> availableIds = pending.candidateBggIds().stream()
                .filter(recommendableIds::contains)
                .toList();
        int expectedCount = Math.min(pending.requestedCount(), availableIds.size());
        List<Integer> selectedIds = draft.candidates().stream()
                .map(CandidateReplyDraft::bggId)
                .toList();
        if (selectedIds.size() != expectedCount) {
            throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        }
        if (!availableIds.containsAll(selectedIds)) {
            throw invalid(Code.PUBLICATION_SELECTION_OUTSIDE_PENDING);
        }
        Permit permit = permit(
                state,
                new PublicationSeed(selectedIds, pending.referenceBggIds(), pending.requestedCount()));
        Set<String> selectedEvidenceIds = selectedIds.stream()
                .map(state.verified::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> observations.narrativeObservations(game, state.research).keySet().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (draft.playerReplyEvidenceIds().stream().anyMatch(id -> !selectedEvidenceIds.contains(id))) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);
        }
        for (CandidateReplyDraft candidate : draft.candidates()) {
            Game game = state.verified.get(candidate.bggId());
            validateReplyEvidence(game, candidate.why(), state);
            if (candidate.tradeoff() != null) validateReplyEvidence(game, candidate.tradeoff(), state);
        }
        return permit;
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

    private void validateReplyEvidence(
            Game game,
            RecommendationReplyDraft reply,
            RecommendationAgentState state) {
        if (game == null
                || reply == null
                || !withinCodePointBounds(
                        reply.text(),
                        RecommendationAgentState.MIN_CARD_REPLY_CODE_POINTS,
                        RecommendationAgentState.MAX_CARD_REPLY_CODE_POINTS)) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        }
        Map<String, CandidateObservation> available = observations.narrativeObservations(game, state.research);
        if (reply.evidenceIds().stream().anyMatch(id -> !available.containsKey(id))) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);
        }
    }

    private RecommendedGame projectModelReply(
            RecommendedGame verifiedProjection,
            CandidateReplyDraft draft,
            RecommendationAgentState state) {
        if (draft == null) throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        Map<String, CandidateObservation> available = observations.narrativeObservations(
                verifiedProjection.game(), state.research);
        List<RecommendationReplyPart> replyParts = new ArrayList<>();
        replyParts.add(replyPart(
                verifiedProjection.game().ranking().bggId(),
                ReplyPartRole.WHY_FIT,
                "whyFit",
                draft.why(),
                available));
        if (draft.tradeoff() != null) {
            replyParts.add(replyPart(
                    verifiedProjection.game().ranking().bggId(),
                    ReplyPartRole.TRADEOFF,
                    "tradeoff",
                    draft.tradeoff(),
                    available));
        }
        List<String> matches = List.of(draft.why().text());
        List<String> tradeoffs = draft.tradeoff() == null
                ? List.of()
                : List.of(draft.tradeoff().text());
        List<RecommendationReason> reasons = replyParts.stream()
                .map(RecommendationReplyPart::claim)
                .map(claim -> new RecommendationReason(
                        ReasonKind.PREFERENCE_INFERENCE,
                        claim.text(),
                        claim.sourceIndexes()))
                .toList();
        return new RecommendedGame(
                verifiedProjection.game(),
                matches,
                tradeoffs,
                reasons,
                verifiedProjection.claims(),
                replyParts);
    }

    private RecommendationReplyPart replyPart(
            int bggId,
            ReplyPartRole role,
            String subject,
            RecommendationReplyDraft draft,
            Map<String, CandidateObservation> available) {
        List<CandidateObservation> evidence = draft.evidenceIds().stream()
                .map(available::get)
                .toList();
        if (evidence.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);
        }
        CandidateClaim claim = new CandidateClaim(
                bggId,
                subject,
                CandidateClaim.Type.PREFERENCE_INFERENCE,
                null,
                CandidateClaim.Relation.OBSERVED,
                draft.text(),
                evidence);
        return new RecommendationReplyPart(role, claim);
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    private boolean withinCodePointBounds(String value, int minimum, int maximum) {
        if (value == null) return false;
        String meaningful = value.strip();
        int codePoints = meaningful.codePointCount(0, meaningful.length());
        return codePoints >= minimum && codePoints <= maximum;
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
        PUBLICATION_STATE_MISSING,
        PUBLICATION_SEED_INVALID,
        PUBLICATION_SELECTION_COUNT_INVALID,
        PUBLICATION_SELECTION_OUTSIDE_PENDING,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_REPLY_INVALID,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        RECOMMENDATION_EVIDENCE_NOT_GROUNDED,
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
