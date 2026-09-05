package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** The single structure, candidate, evidence-ownership, and publication boundary for recommendation prose. */
final class RecommendationPublication {

    private static final Set<String> SEARCH_PUBLICATION_FIELDS = Set.of("selections");
    private static final Set<String> FOLLOW_UP_PUBLICATION_FIELDS =
            Set.of("publicationCount", "selections");
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
                : positiveInteger(root.path("publicationCount"));
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

        List<CandidateEvidence> candidates = new ArrayList<>();
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
            candidates.add(selectionEvidence(state, game, selection, localizedFailures));
            selectedGames.add(game);
        }

        if (selectedGames.isEmpty()) {
            if (firstCandidateFailure != null) throw firstCandidateFailure;
            throw invalid(Code.PUBLICATION_SELECTION_COUNT_INVALID);
        }

        String playerReply = null;
        // A complete answer may refer to every selection. Once any identity or evidence binding changes,
        // preserve the verified cards without publishing prose whose full support can no longer be established.
        if (!candidateSetChanged && localizedFailures.isEmpty()) {
            try {
                playerReply = playerText(root.path("playerReply"));
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

    Consumer<ToolCall> previewPublisher(
            RecommendationAgentState state,
            Consumer<BoardGameRecommendationAgent.RecommendationPart> listener) {
        Objects.requireNonNull(listener, "recommendation part listener is required");
        return new PreviewPublisher(state, listener)::accept;
    }

    private BoardGameRecommendationAgent.RecommendationPart previewCandidate(
            RecommendationAgentState state,
            String selectionJson) {
        try {
            JsonNode selection = parse(selectionJson);
            requireObject(selection, SELECTION_REQUIRED_FIELDS);
            int bggId = positiveInteger(selection.path("bggId"));
            PublicationSeed pending = Objects.requireNonNull(
                    state.pendingPublicationSeed, "pending recommendation publication is required");
            Game game = validatedCandidate(state, pending, runtime.recommendableIds(state), bggId);
            RecommendedGame preview = verifiedCard(game);
            CandidateEvidence evidence = selectionEvidence(state, game, selection, new LinkedHashSet<>());
            return new BoardGameRecommendationAgent.RecommendationPart(
                    preview,
                    runtime.responseSources(state, List.of(preview), new LinkedHashSet<>(evidence.evidenceIds())));
        } catch (InvalidPublication | IllegalArgumentException ignored) {
            return null;
        }
    }

    private CandidateEvidence selectionEvidence(
            RecommendationAgentState state,
            Game game,
            JsonNode selection,
            Set<Code> localizedFailures) {
        Map<String, CandidateObservation> availableEvidence =
                observations.narrativeObservations(game, state.research);
        try {
            return new CandidateEvidence(
                    game.ranking().bggId(),
                    evidenceIds(selection.path("internalEvidenceIds"), 1, availableEvidence.keySet()));
        } catch (InvalidPublication failure) {
            localizedFailures.add(failure.code());
            return new CandidateEvidence(game.ranking().bggId(), List.of());
        }
    }

    private final class PreviewPublisher {
        private final RecommendationAgentState state;
        private final Consumer<BoardGameRecommendationAgent.RecommendationPart> listener;
        private final Set<Integer> emittedIds = new LinkedHashSet<>();
        private int inspectedObjects;

        private PreviewPublisher(
                RecommendationAgentState state,
                Consumer<BoardGameRecommendationAgent.RecommendationPart> listener) {
            this.state = state;
            this.listener = listener;
        }

        private void accept(ToolCall call) {
            if (!RECOMMEND_TOOL.equals(call.name())) return;
            String accumulatedArguments = call.argumentsJson();
            List<String> complete = completeSelectionObjects(accumulatedArguments);
            int limit = previewLimit(state, accumulatedArguments);
            for (int index = inspectedObjects; index < complete.size(); index++) {
                inspectedObjects++;
                if (emittedIds.size() >= limit) continue;
                BoardGameRecommendationAgent.RecommendationPart part =
                        previewCandidate(state, complete.get(index));
                if (part == null || !emittedIds.add(part.game().game().ranking().bggId())) continue;
                listener.accept(part);
            }
        }
    }

    private int previewLimit(RecommendationAgentState state, String accumulatedArguments) {
        int requested;
        if (state.activeSearch != null) {
            requested = state.activeSearch.requestedCount() == null
                    ? maximumResultCount
                    : state.activeSearch.requestedCount();
        } else {
            requested = completedPositiveIntegerField(accumulatedArguments, "publicationCount");
        }
        return Math.min(maximumResultCount, Math.max(0, requested));
    }

    private int completedPositiveIntegerField(String json, String field) {
        int fieldIndex = json == null ? -1 : json.indexOf('"' + field + '"');
        if (fieldIndex < 0) return 0;
        int colon = json.indexOf(':', fieldIndex + field.length() + 2);
        if (colon < 0) return 0;
        int cursor = colon + 1;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        int start = cursor;
        while (cursor < json.length() && Character.isDigit(json.charAt(cursor))) cursor++;
        if (cursor == start || cursor == json.length()) return 0;
        try {
            return Integer.parseInt(json.substring(start, cursor));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static List<String> completeSelectionObjects(String json) {
        if (json == null || json.isEmpty()) return List.of();
        int field = json.indexOf("\"selections\"");
        if (field < 0) return List.of();
        int array = json.indexOf('[', field + 12);
        if (array < 0) return List.of();
        List<String> objects = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int index = array + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (character == '}') {
                if (depth == 0) return List.copyOf(objects);
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, index + 1));
                    start = -1;
                }
            } else if (character == ']' && depth == 0) {
                break;
            }
        }
        return List.copyOf(objects);
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            PreparedPublication prepared,
            String locale) {
        Permit permit = prepared.permit();
        PublicationDraft draft = prepared.draft();
        List<RecommendedGame> games = permit.selectedGames().stream()
                .map(this::verifiedCard)
                .toList();
        Set<String> publishedEvidenceIds = draft.candidates().stream()
                .flatMap(candidate -> candidate.evidenceIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (draft.playerReply() != null) {
            state.actions.add("MODEL_AUTHORED_RECOMMENDATION");
        }
        if (prepared.localized()) state.actions.add("RECOMMENDATION_NARRATIVE_PARTIAL");
        return response(
                state,
                permit,
                games,
                draft.playerReply() == null ? "" : draft.playerReply(),
                publishedEvidenceIds,
                locale,
                prepared.localized());
    }

    private ConversationResponse response(
            RecommendationAgentState state,
            Permit permit,
            List<RecommendedGame> games,
            String assistantMessage,
            Set<String> publishedEvidenceIds,
            String locale,
            boolean recovered) {
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

    private String playerText(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(Code.RECOMMENDATION_REPLY_INVALID);
        }
        return value.asText();
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

    private RecommendedGame verifiedCard(Game game) {
        return new RecommendedGame(game, List.of(), List.of());
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
            List<CandidateEvidence> candidates) {
        PublicationDraft {
            candidates = List.copyOf(candidates);
        }
    }

    private record CandidateEvidence(
            int bggId,
            List<String> evidenceIds) {
        CandidateEvidence {
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
