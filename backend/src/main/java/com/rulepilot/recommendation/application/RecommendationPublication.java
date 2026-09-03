package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReplyPart;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The single structure, candidate, evidence-ownership, and publication boundary for recommendation prose. */
final class RecommendationPublication {

    static final int PLAYER_REPLY_MAX_CODE_POINTS = 600;
    static final int WHY_FIT_MAX_CODE_POINTS = 400;
    static final int TRADEOFF_MAX_CODE_POINTS = 240;

    private static final Set<String> SEARCH_PUBLICATION_FIELDS = Set.of("playerReply", "selections");
    private static final Set<String> FOLLOW_UP_PUBLICATION_FIELDS =
            Set.of("requestedCount", "playerReply", "selections");
    private static final Set<String> SELECTION_REQUIRED_FIELDS = Set.of("bggId");

    private final BoardGameRecommendationSelector selector;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions observations;
    private final RecommendationReActLoop runtime;
    private final int maximumResultCount;
    private final ObjectMapper publicationJson;

    RecommendationPublication(
            BoardGameRecommendationSelector selector,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions observations,
            RecommendationReActLoop runtime,
            BoardGameRecommendationProperties properties,
            ObjectMapper json) {
        this.selector = selector;
        this.evidenceReview = evidenceReview;
        this.observations = observations;
        this.runtime = runtime;
        maximumResultCount = properties.resultCount();
        publicationJson = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PreparedPublication prepare(RecommendationAgentState state, String argumentsJson) {
        if (state == null) throw invalid(Code.PUBLICATION_STATE_MISSING);
        PublicationSeed pending = state.pendingPublicationSeed;
        if (pending == null) throw invalid(Code.PUBLICATION_STATE_MISSING);

        JsonNode root = parse(argumentsJson);
        boolean searchOwnsCount = state.activeSearch != null;
        requireObject(root, searchOwnsCount ? SEARCH_PUBLICATION_FIELDS : FOLLOW_UP_PUBLICATION_FIELDS);
        List<Integer> currentlyRecommendable = runtime.recommendableIds(state);
        List<Integer> allowedCandidateIds = pending.candidateBggIds().stream()
                .filter(currentlyRecommendable::contains)
                .filter(id -> {
                    Game game = state.verified.get(id);
                    return game != null && !observations.narrativeObservations(game, state.research).isEmpty();
                })
                .toList();
        Integer explicitSearchCount = searchOwnsCount
                ? state.activeSearch.requestedCount()
                : null;
        int requestedCount = searchOwnsCount
                ? explicitSearchCount == null
                        ? Math.min(maximumResultCount, allowedCandidateIds.size())
                        : explicitSearchCount
                : positiveInteger(root.path("requestedCount"));
        int maximumSelections = Math.min(
                maximumResultCount,
                Math.min(requestedCount, allowedCandidateIds.size()));
        JsonNode rawSelections = root.path("selections");
        JsonNode selections = selectionArray(rawSelections);
        if (!rawSelections.isArray()) {
            state.actions.add("RECOMMENDATION_WIRE_FORMAT_NORMALIZED");
        }
        if (selections.isEmpty()
                || maximumSelections == 0) {
            throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        }

        List<CandidateReplyDraft> candidates = new ArrayList<>();
        List<Game> selectedGames = new ArrayList<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        Set<Code> localizedFailures = new LinkedHashSet<>();
        InvalidPublication firstCandidateFailure = null;
        boolean candidateSetChanged = selections.size() > maximumSelections;
        if (selections.size() > maximumSelections) {
            localizedFailures.add(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        }
        for (int index = 0; index < selections.size(); index++) {
            if (selectedGames.size() == maximumSelections) break;
            JsonNode selection = selections.get(index);
            Game game;
            int bggId;
            try {
                requireObject(selection, SELECTION_REQUIRED_FIELDS);
                bggId = positiveInteger(selection.path("bggId"));
                if (!selectedIds.add(bggId)) {
                    throw invalid(Code.DUPLICATE_SELECTION);
                }
                game = validatedCandidate(
                        state,
                        pending,
                        currentlyRecommendable,
                        bggId);
            } catch (InvalidPublication failure) {
                if (firstCandidateFailure == null) firstCandidateFailure = failure;
                candidateSetChanged = true;
                localizedFailures.add(failure.code());
                continue;
            }
            Map<String, CandidateObservation> availableEvidence =
                    observations.narrativeObservations(game, state.research);
            String whyFit = null;
            String tradeoff = null;
            List<String> evidenceIds = List.of();
            try {
                whyFit = playerText(selection.path("whyFit"), WHY_FIT_MAX_CODE_POINTS);
                evidenceIds = evidenceIds(
                        selection.path("internalEvidenceIds"),
                        1,
                        availableEvidence.keySet());
                if (selection.has("tradeoff")) {
                    try {
                        tradeoff = playerText(selection.path("tradeoff"), TRADEOFF_MAX_CODE_POINTS);
                    } catch (InvalidPublication failure) {
                        localizedFailures.add(failure.code());
                    }
                }
            } catch (InvalidPublication failure) {
                localizedFailures.add(failure.code());
                whyFit = null;
                tradeoff = null;
                evidenceIds = List.of();
            }
            candidates.add(new CandidateReplyDraft(bggId, whyFit, tradeoff, evidenceIds));
            selectedGames.add(game);
        }

        if (selectedGames.isEmpty()) {
            if (firstCandidateFailure != null) throw firstCandidateFailure;
            throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        }

        String playerReply = null;
        if (!candidateSetChanged) {
            try {
                playerReply = playerText(root.path("playerReply"), PLAYER_REPLY_MAX_CODE_POINTS);
            } catch (InvalidPublication failure) {
                localizedFailures.add(failure.code());
                playerReply = null;
            }
        }
        PublicationDraft draft = new PublicationDraft(playerReply, candidates);
        RecommendationShortfall shortfall = (explicitSearchCount != null || !searchOwnsCount)
                        && selectedGames.size() < requestedCount
                ? new RecommendationShortfall(requestedCount, selectedGames.size())
                : null;
        return new PreparedPublication(
                new Permit(requestedCount, selectedGames, shortfall),
                draft,
                List.copyOf(localizedFailures));
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            PreparedPublication prepared,
            String locale) {
        Permit permit = prepared.permit();
        PublicationDraft draft = prepared.draft();
        Map<Integer, CandidateReplyDraft> draftsById = draft.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateReplyDraft::bggId,
                        candidate -> candidate,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<RecommendedGame> games = permit.selectedGames().stream()
                .map(game -> projectModelReply(
                        game,
                        Objects.requireNonNull(draftsById.get(game.ranking().bggId())),
                        state,
                        locale))
                .toList();
        if (draft.playerReply() != null
                || draft.candidates().stream().anyMatch(candidate -> candidate.whyFit() != null)) {
            state.actions.add("MODEL_AUTHORED_RECOMMENDATION");
        }
        if (prepared.localized()) state.actions.add("RECOMMENDATION_NARRATIVE_PARTIAL");
        return response(
                state,
                permit,
                games,
                draft.playerReply() == null ? localizedReply(locale) : draft.playerReply(),
                locale,
                prepared.localized());
    }

    private ConversationResponse response(
            RecommendationAgentState state,
            Permit permit,
            List<RecommendedGame> games,
            String assistantMessage,
            String locale,
            boolean recovered) {
        Set<String> publishedEvidenceIds = new LinkedHashSet<>();
        games.stream()
                .flatMap(game -> java.util.stream.Stream.concat(
                        game.claims().stream(),
                        game.replyParts().stream().map(RecommendationReplyPart::claim)))
                .flatMap(claim -> claim.evidence().stream())
                .map(CandidateObservation::id)
                .forEach(publishedEvidenceIds::add);

        List<String> responseActions = new ArrayList<>(state.actions);
        if (permit.shortfall() != null) responseActions.add("RECOMMENDATION_VERIFIED_SET_SHORTFALL");
        responseActions.add("RECOMMEND_GAMES");
        List<BoardGameRecommendationAgent.ResearchSource> sources =
                runtime.responseSources(state, games, publishedEvidenceIds);
        ConversationResponse response = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                assistantMessage,
                state.selectionProfile(),
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                sources,
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        recovered,
                        responseActions,
                        state.elapsedMs(),
                        state.modelCallElapsedMs),
                games,
                state.comparison,
                permit.shortfall());

        state.finalResponseEvidenceIds.addAll(publishedEvidenceIds);
        state.actions.clear();
        state.actions.addAll(responseActions);
        return response;
    }

    private JsonNode parse(String argumentsJson) {
        try {
            JsonNode value = publicationJson.readTree(argumentsJson);
            if (value == null) throw invalid(Code.INVALID_JSON);
            return value;
        } catch (JsonProcessingException failure) {
            throw invalid(Code.INVALID_JSON);
        }
    }

    /** Decodes one provider-style nested JSON value, then subjects it to the ordinary boundary. */
    private JsonNode selectionArray(JsonNode value) {
        if (value != null && value.isArray()) return value;
        if (value != null && value.isTextual()) {
            JsonNode decoded = parse(value.asText());
            if (decoded.isArray()) return decoded;
        }
        throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
    }

    private void requireObject(JsonNode value, Set<String> requiredFields) {
        if (value == null || !value.isObject()) {
            throw invalid(Code.RECOMMENDATION_OBJECT_INVALID);
        }
        List<String> missing = requiredFields.stream()
                .filter(field -> !value.has(field))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw invalid(Code.RECOMMENDATION_REQUIRED_FIELD_MISSING);
        }
    }

    private String playerText(JsonNode value, int maximumCodePoints) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        }
        String text = value.asText();
        if (text.codePointCount(0, text.length()) > maximumCodePoints) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        }
        return text;
    }

    private int positiveInteger(JsonNode value) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() <= 0) {
            throw invalid(Code.FINAL_ID_NOT_VERIFIED);
        }
        return value.intValue();
    }

    private Game validatedCandidate(
            RecommendationAgentState state,
            PublicationSeed pending,
            List<Integer> currentlyRecommendable,
            int bggId) {
        Game game = state.verified.get(bggId);
        if (game == null) throw invalid(Code.FINAL_ID_NOT_VERIFIED);
        if (!pending.candidateBggIds().contains(bggId)) {
            throw invalid(Code.PUBLICATION_SELECTION_OUTSIDE_PENDING);
        }
        if (state.excludedIds.contains(bggId)) {
            throw invalid(Code.FINAL_ID_EXCLUDED);
        }
        boolean exactTitle = state.activeSearch != null
                && state.activeSearch.title() != null
                && state.activeSearch.title().match() == RecommendationAgentState.TitleMatch.EXACT;
        boolean verifiedFollowUp = state.activeSearch == null;
        if (state.previouslyShownIds.contains(bggId) && !exactTitle && !verifiedFollowUp) {
            throw invalid(Code.FINAL_ID_PREVIOUSLY_SHOWN);
        }
        if (!currentlyRecommendable.contains(bggId)
                || !selector.eligible(game, state.selectionProfile())) {
            throw invalid(Code.FINAL_ID_FAILS_HARD_GATES);
        }
        if (observations.narrativeObservations(game, state.research).isEmpty()) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
        }
        return game;
    }

    private List<String> evidenceIds(
            JsonNode value,
            int minimumItems,
            Set<String> allowedEvidenceIds) {
        if (value == null
                || !value.isArray()
                || value.size() < minimumItems) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_LIST_INVALID);
        }
        List<String> evidenceIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_LIST_INVALID);
            }
            String evidenceId = item.asText();
            if (!seen.add(evidenceId)) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_LIST_INVALID);
            }
            if (!allowedEvidenceIds.contains(evidenceId)) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED);
            }
            evidenceIds.add(evidenceId);
        }
        return List.copyOf(evidenceIds);
    }

    private RecommendedGame projectModelReply(
            Game game,
            CandidateReplyDraft draft,
            RecommendationAgentState state,
            String locale) {
        Map<String, CandidateObservation> available = observations.narrativeObservations(
                game, state.research);
        List<RecommendationReplyPart> replyParts = new ArrayList<>();
        if (draft.whyFit() != null) {
            replyParts.add(replyPart(
                    game.ranking().bggId(),
                    ReplyPartRole.WHY_FIT,
                    "whyFit",
                    draft.whyFit(),
                    draft.evidenceIds(),
                    available));
        }
        if (draft.tradeoff() != null) {
            replyParts.add(replyPart(
                    game.ranking().bggId(),
                    ReplyPartRole.TRADEOFF,
                    "tradeoff",
                    draft.tradeoff(),
                    draft.evidenceIds(),
                    available));
        }
        List<CandidateClaim> fitClaims = selector.fitClaims(
                game,
                state.selectionProfile(),
                runtime.chinese(locale));
        return new RecommendedGame(game, fitClaims, replyParts);
    }

    private RecommendationReplyPart replyPart(
            int bggId,
            ReplyPartRole role,
            String subject,
            String text,
            List<String> evidenceIds,
            Map<String, CandidateObservation> available) {
        List<CandidateObservation> evidence = evidenceIds.stream()
                .map(id -> Objects.requireNonNull(available.get(id)))
                .toList();
        CandidateClaim claim = new CandidateClaim(
                bggId,
                subject,
                CandidateClaim.Type.PREFERENCE_INFERENCE,
                null,
                CandidateClaim.Relation.OBSERVED,
                text,
                evidence);
        return new RecommendationReplyPart(role, claim);
    }

    private String localizedReply(String locale) {
        return runtime.chinese(locale)
                ? "下面保留了已经核验的候选；未通过证据校验的说明已省略。"
                : "The verified candidates are preserved below; explanations that failed evidence validation were omitted.";
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    record PreparedPublication(Permit permit, PublicationDraft draft, List<Code> localizedFailures) {
        PreparedPublication {
            localizedFailures = List.copyOf(localizedFailures);
        }

        boolean localized() {
            return !localizedFailures.isEmpty();
        }
    }

    record Permit(
            int requestedCount,
            List<Game> selectedGames,
            RecommendationShortfall shortfall) {
        Permit {
            selectedGames = List.copyOf(selectedGames);
        }
    }

    private record PublicationDraft(
            String playerReply,
            List<CandidateReplyDraft> candidates) {
        PublicationDraft {
            candidates = List.copyOf(candidates);
        }
    }

    private record CandidateReplyDraft(
            int bggId,
            String whyFit,
            String tradeoff,
            List<String> evidenceIds) {
        CandidateReplyDraft {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    enum Code {
        INVALID_JSON,
        RECOMMENDATION_OBJECT_INVALID,
        RECOMMENDATION_REQUIRED_FIELD_MISSING,
        PUBLICATION_STATE_MISSING,
        PUBLICATION_SELECTION_COUNT_INVALID,
        PUBLICATION_SELECTION_OUTSIDE_PENDING,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_REPLY_INVALID,
        RECOMMENDATION_EVIDENCE_LIST_INVALID,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        RECOMMENDATION_EVIDENCE_NOT_GROUNDED
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
