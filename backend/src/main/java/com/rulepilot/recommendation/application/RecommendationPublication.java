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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
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

    private static final Set<String> ROOT_FIELDS =
            Set.of("playerReply", "playerReplyEvidenceIds", "selections");
    private static final Set<String> SELECTION_REQUIRED_FIELDS = Set.of("bggId", "why");
    private static final Set<String> SELECTION_OPTIONAL_FIELDS = Set.of("tradeoff");
    private static final Set<String> REPLY_FIELDS = Set.of("text", "internalEvidenceIds");

    private final BoardGameRecommendationSelector selector;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions observations;
    private final RecommendationReActLoop runtime;
    private final ObjectMapper publicationJson;

    RecommendationPublication(
            BoardGameRecommendationSelector selector,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions observations,
            RecommendationReActLoop runtime,
            ObjectMapper json) {
        this.selector = selector;
        this.evidenceReview = evidenceReview;
        this.observations = observations;
        this.runtime = runtime;
        publicationJson = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PreparedPublication prepare(RecommendationAgentState state, String argumentsJson) {
        if (state == null) throw invalid(Code.PUBLICATION_STATE_MISSING, "$", Map.of());
        PublicationSeed pending = state.pendingPublicationSeed;
        if (pending == null) throw invalid(Code.PUBLICATION_STATE_MISSING, "$", Map.of());

        JsonNode root = parse(argumentsJson);
        requireObject(root, "$", ROOT_FIELDS, Set.of());
        String playerReply = playerText(root.path("playerReply"), "$.playerReply");

        List<Integer> currentlyRecommendable = runtime.recommendableIds(state);
        List<Integer> allowedCandidateIds = pending.candidateBggIds().stream()
                .filter(currentlyRecommendable::contains)
                .filter(id -> {
                    Game game = state.verified.get(id);
                    return game != null && !observations.narrativeObservations(game, state.research).isEmpty();
                })
                .toList();
        int maximumSelections = Math.min(pending.requestedCount(), allowedCandidateIds.size());
        JsonNode selections = root.path("selections");
        if (!selections.isArray()
                || selections.isEmpty()
                || maximumSelections == 0
                || selections.size() > maximumSelections) {
            throw invalid(
                    Code.PUBLICATION_SELECTION_COUNT_INVALID,
                    "$.selections",
                    Map.of("maximumSelections", maximumSelections));
        }

        List<CandidateReplyDraft> candidates = new ArrayList<>();
        List<Game> selectedGames = new ArrayList<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        Set<String> selectedEvidenceIds = new LinkedHashSet<>();
        for (int index = 0; index < selections.size(); index++) {
            String selectionPath = "$.selections[" + index + "]";
            JsonNode selection = selections.get(index);
            requireObject(selection, selectionPath, SELECTION_REQUIRED_FIELDS, SELECTION_OPTIONAL_FIELDS);
            int bggId = positiveInteger(selection.path("bggId"), selectionPath + ".bggId");
            if (!selectedIds.add(bggId)) {
                throw invalid(
                        Code.DUPLICATE_SELECTION,
                        selectionPath + ".bggId",
                        Map.of("bggId", bggId));
            }
            Game game = validatedCandidate(state, pending, currentlyRecommendable, bggId, selectionPath + ".bggId");
            Map<String, CandidateObservation> availableEvidence =
                    observations.narrativeObservations(game, state.research);
            RecommendationReplyDraft why = replyDraft(
                    selection.path("why"),
                    selectionPath + ".why",
                    availableEvidence);
            RecommendationReplyDraft tradeoff = selection.has("tradeoff")
                    ? replyDraft(
                            selection.path("tradeoff"),
                            selectionPath + ".tradeoff",
                            availableEvidence)
                    : null;
            candidates.add(new CandidateReplyDraft(bggId, why, tradeoff));
            selectedGames.add(game);
            selectedEvidenceIds.addAll(availableEvidence.keySet());
        }

        List<String> playerReplyEvidenceIds = evidenceIds(
                root.path("playerReplyEvidenceIds"),
                "$.playerReplyEvidenceIds",
                0,
                selectedEvidenceIds);
        PublicationDraft draft = new PublicationDraft(playerReply, playerReplyEvidenceIds, candidates);
        int requestedCount = pending.requestedCount();
        RecommendationShortfall shortfall = selectedGames.size() < requestedCount
                ? new RecommendationShortfall(requestedCount, selectedGames.size())
                : null;
        return new PreparedPublication(new Permit(requestedCount, selectedGames, shortfall), draft);
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
        List<RecommendedGame> games = selector.present(
                        permit.selectedGames(),
                        state.profile,
                        runtime.chinese(locale))
                .stream()
                .map(game -> projectModelReply(
                        game,
                        Objects.requireNonNull(draftsById.get(game.game().ranking().bggId())),
                        state))
                .toList();
        state.actions.add("MODEL_AUTHORED_RECOMMENDATION");
        return response(
                state,
                permit,
                games,
                draft.playerReply(),
                draft.playerReplyEvidenceIds(),
                locale);
    }

    private ConversationResponse response(
            RecommendationAgentState state,
            Permit permit,
            List<RecommendedGame> games,
            String assistantMessage,
            List<String> playerReplyEvidenceIds,
            String locale) {
        Set<String> publishedEvidenceIds = new LinkedHashSet<>(playerReplyEvidenceIds);
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

    private JsonNode parse(String argumentsJson) {
        try {
            JsonNode value = publicationJson.readTree(argumentsJson);
            if (value == null) throw invalid(Code.INVALID_JSON, "$", Map.of());
            return value;
        } catch (JsonProcessingException failure) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("message", failure.getOriginalMessage());
            if (failure.getLocation() != null) {
                details.put("line", failure.getLocation().getLineNr());
                details.put("column", failure.getLocation().getColumnNr());
            }
            throw invalid(Code.INVALID_JSON, "$", details);
        }
    }

    private void requireObject(
            JsonNode value,
            String path,
            Set<String> requiredFields,
            Set<String> optionalFields) {
        if (value == null || !value.isObject()) {
            throw invalid(Code.RECOMMENDATION_OBJECT_INVALID, path, Map.of());
        }
        List<String> actual = new ArrayList<>();
        value.fieldNames().forEachRemaining(actual::add);
        List<String> missing = requiredFields.stream()
                .filter(field -> !value.has(field))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw invalid(
                    Code.RECOMMENDATION_REQUIRED_FIELD_MISSING,
                    path + "." + missing.getFirst(),
                    Map.of("missingFields", missing));
        }
        Set<String> allowed = new LinkedHashSet<>(requiredFields);
        allowed.addAll(optionalFields);
        List<String> unexpected = actual.stream()
                .filter(field -> !allowed.contains(field))
                .distinct()
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            throw invalid(
                    Code.RECOMMENDATION_UNEXPECTED_FIELD,
                    path + "." + unexpected.getFirst(),
                    Map.of("unexpectedFields", unexpected, "allowedFields", allowed.stream().sorted().toList()));
        }
    }

    private String playerText(JsonNode value, String path) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID, path, Map.of());
        }
        return value.asText();
    }

    private int positiveInteger(JsonNode value, String path) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() <= 0) {
            throw invalid(Code.FINAL_ID_NOT_VERIFIED, path, Map.of());
        }
        return value.intValue();
    }

    private Game validatedCandidate(
            RecommendationAgentState state,
            PublicationSeed pending,
            List<Integer> currentlyRecommendable,
            int bggId,
            String path) {
        Game game = state.verified.get(bggId);
        if (game == null) throw invalid(Code.FINAL_ID_NOT_VERIFIED, path, Map.of("bggId", bggId));
        if (!pending.candidateBggIds().contains(bggId)) {
            throw invalid(
                    Code.PUBLICATION_SELECTION_OUTSIDE_PENDING,
                    path,
                    Map.of("bggId", bggId));
        }
        if (state.excludedIds.contains(bggId)) {
            throw invalid(Code.FINAL_ID_EXCLUDED, path, Map.of("bggId", bggId));
        }
        if (state.previouslyShownIds.contains(bggId) && !state.targetGameIds.contains(bggId)) {
            throw invalid(Code.FINAL_ID_PREVIOUSLY_SHOWN, path, Map.of("bggId", bggId));
        }
        if (state.comparisonReferenceIds.contains(bggId)) {
            throw invalid(Code.FINAL_ID_IS_COMPARISON_REFERENCE, path, Map.of("bggId", bggId));
        }
        if (!currentlyRecommendable.contains(bggId)
                || (!state.targetGameIds.contains(bggId) && !selector.eligible(game, state.profile))) {
            throw invalid(Code.FINAL_ID_FAILS_HARD_GATES, path, Map.of("bggId", bggId));
        }
        if (observations.narrativeObservations(game, state.research).isEmpty()) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED, path, Map.of("bggId", bggId));
        }
        return game;
    }

    private RecommendationReplyDraft replyDraft(
            JsonNode value,
            String path,
            Map<String, CandidateObservation> availableEvidence) {
        requireObject(value, path, REPLY_FIELDS, Set.of());
        String text = playerText(value.path("text"), path + ".text");
        List<String> evidenceIds = evidenceIds(
                value.path("internalEvidenceIds"),
                path + ".internalEvidenceIds",
                1,
                availableEvidence.keySet());
        return new RecommendationReplyDraft(text, evidenceIds);
    }

    private List<String> evidenceIds(
            JsonNode value,
            String path,
            int minimumItems,
            Set<String> allowedEvidenceIds) {
        if (value == null
                || !value.isArray()
                || value.size() < minimumItems) {
            throw invalid(
                    Code.RECOMMENDATION_EVIDENCE_LIST_INVALID,
                    path,
                    Map.of("minimumItems", minimumItems));
        }
        List<String> evidenceIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            String itemPath = path + "[" + index + "]";
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_LIST_INVALID, itemPath, Map.of());
            }
            String evidenceId = item.asText();
            if (!seen.add(evidenceId)) {
                throw invalid(
                        Code.RECOMMENDATION_EVIDENCE_LIST_INVALID,
                        itemPath,
                        Map.of("duplicateEvidenceId", evidenceId));
            }
            if (!allowedEvidenceIds.contains(evidenceId)) {
                throw invalid(
                        Code.RECOMMENDATION_EVIDENCE_NOT_GROUNDED,
                        itemPath,
                        Map.of("submittedEvidenceId", evidenceId));
            }
            evidenceIds.add(evidenceId);
        }
        return List.copyOf(evidenceIds);
    }

    private RecommendedGame projectModelReply(
            RecommendedGame verifiedProjection,
            CandidateReplyDraft draft,
            RecommendationAgentState state) {
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
                .map(id -> Objects.requireNonNull(available.get(id)))
                .toList();
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

    private InvalidPublication invalid(Code code, String path, Map<String, ?> details) {
        return new InvalidPublication(code, path, details);
    }

    record PreparedPublication(Permit permit, PublicationDraft draft) {}

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
            List<String> playerReplyEvidenceIds,
            List<CandidateReplyDraft> candidates) {
        PublicationDraft {
            playerReplyEvidenceIds = List.copyOf(playerReplyEvidenceIds);
            candidates = List.copyOf(candidates);
        }
    }

    private record CandidateReplyDraft(
            int bggId,
            RecommendationReplyDraft why,
            RecommendationReplyDraft tradeoff) {}

    private record RecommendationReplyDraft(String text, List<String> evidenceIds) {
        RecommendationReplyDraft {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    enum Code {
        INVALID_JSON,
        RECOMMENDATION_OBJECT_INVALID,
        RECOMMENDATION_REQUIRED_FIELD_MISSING,
        RECOMMENDATION_UNEXPECTED_FIELD,
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
        private final String path;
        private final Map<String, ?> details;

        private InvalidPublication(Code code, String path, Map<String, ?> details) {
            super(code.name() + " at " + path);
            this.code = code;
            this.path = path;
            this.details = details == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }

        Code code() {
            return code;
        }

        String path() {
            return path;
        }

        Map<String, ?> details() {
            return details;
        }
    }
}
