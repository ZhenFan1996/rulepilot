package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** The single deterministic publication boundary for model-written recommendation decisions and prose. */
final class RecommendationPublication {

    private static final int MAX_MESSAGE_CODE_POINTS = 1_200;
    private static final int MAX_MESSAGE_BLOCK_CODE_POINTS = 700;
    private static final int MAX_REASON_CODE_POINTS = 280;
    private static final int MAX_TRADEOFF_CODE_POINTS = 220;

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

    Permit permit(
            JsonNode decision,
            RecommendationAgentState state,
            PublicationSeed seed) {
        Objects.requireNonNull(state, "recommendation state is required");
        Objects.requireNonNull(seed, "publication seed is required");
        if (seed.candidateUse() == CandidateUse.CONTINUE_REACT) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        requireObject(decision, Set.of("requestedCount", "selections", "referenceBggIds"));
        int requestedCount = integer(
                decision.path("requestedCount"),
                1,
                state.maximumRecommendationResults,
                Code.SELECTION_COUNT_INVALID);

        Set<Integer> currentRecommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        if (!currentRecommendable.containsAll(seed.candidateBggIds())) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        JsonNode selections = decision.path("selections");
        int expectedSelectionCount = Math.min(requestedCount, seed.candidateBggIds().size());
        if (!selections.isArray()
                || selections.size() != expectedSelectionCount
                || selections.isEmpty()
                || selections.size() > state.maximumRecommendationResults) {
            throw invalid(Code.SELECTION_COUNT_INVALID);
        }

        List<Game> selectedGames = new ArrayList<>();
        Map<Integer, Map<String, CandidateObservation>> allowedEvidence = new LinkedHashMap<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        for (JsonNode selection : selections) {
            requireObject(selection, Set.of("bggId"));
            int bggId = integer(selection.path("bggId"), 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID);
            if (!selectedIds.add(bggId)) throw invalid(Code.DUPLICATE_SELECTION);
            if (!seed.candidateBggIds().contains(bggId)) throw invalid(Code.FINAL_ID_NOT_IN_PUBLICATION_SEED);
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
            Map<String, CandidateObservation> available = observations.narrativeObservations(game, state.research);
            if (available.isEmpty()) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            }
            selectedGames.add(game);
            allowedEvidence.put(bggId, available);
        }

        List<Integer> referenceIds = ids(decision.path("referenceBggIds"), 0, 2);
        if (!seed.referenceBggIds().containsAll(referenceIds)) {
            throw invalid(Code.REFERENCE_ID_NOT_IN_PUBLICATION_SEED);
        }
        if (referenceIds.stream().anyMatch(selectedIds::contains)) {
            throw invalid(Code.REFERENCE_ID_SELECTED);
        }
        List<Game> referenceGames = new ArrayList<>();
        for (Integer referenceId : referenceIds) {
            Game reference = state.verified.get(referenceId);
            if (reference == null || !state.comparisonReferenceIds.contains(referenceId)) {
                throw invalid(Code.REFERENCE_ID_NOT_VERIFIED);
            }
            referenceGames.add(reference);
            allowedEvidence.put(referenceId, observations.narrativeObservations(reference, state.research));
        }

        RecommendationShortfall shortfall = seed.candidateBggIds().size() < requestedCount
                ? new RecommendationShortfall(requestedCount, seed.candidateBggIds().size())
                : null;
        return new Permit(
                requestedCount,
                List.copyOf(selectedGames),
                List.copyOf(referenceGames),
                shortfall,
                immutableEvidence(allowedEvidence));
    }

    Session open(
            Permit permit,
            RecommendationAgentState state,
            String locale,
            Consumer<String> accumulatedAnswerListener) {
        return new Session(
                Objects.requireNonNull(permit, "publication permit is required"),
                Objects.requireNonNull(state, "recommendation state is required"),
                locale,
                Objects.requireNonNull(accumulatedAnswerListener, "answer listener is required"));
    }

    private Map<Integer, Map<String, CandidateObservation>> immutableEvidence(
            Map<Integer, Map<String, CandidateObservation>> values) {
        LinkedHashMap<Integer, Map<String, CandidateObservation>> copy = new LinkedHashMap<>();
        values.forEach((id, evidence) -> copy.put(id, Map.copyOf(evidence)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private void requireObject(JsonNode value, Set<String> required) {
        if (value == null || !value.isObject()) throw invalid(Code.OBJECT_REQUIRED);
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid(Code.OBJECT_FIELDS_INVALID);
    }

    private int integer(JsonNode value, int minimum, int maximum, Code code) {
        if (!value.canConvertToInt()) throw invalid(code);
        int parsed = value.intValue();
        if (parsed < minimum || parsed > maximum) throw invalid(code);
        return parsed;
    }

    private List<Integer> ids(JsonNode value, int minimumItems, int maximumItems) {
        if (!value.isArray() || value.size() < minimumItems || value.size() > maximumItems) {
            throw invalid(Code.ID_LIST_INVALID);
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : value) values.add(integer(item, 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID));
        if (values.stream().distinct().count() != values.size()) throw invalid(Code.DUPLICATE_LIST_VALUE);
        return List.copyOf(values);
    }

    private List<String> strings(JsonNode value, int minimumItems, int maximumItems) {
        if (!value.isArray() || value.size() < minimumItems || value.size() > maximumItems) {
            throw invalid(Code.EVIDENCE_LIST_INVALID);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid(Code.EVIDENCE_LIST_INVALID);
            String text = item.asText().strip();
            int length = text.codePointCount(0, text.length());
            if (length < 3 || length > 80) throw invalid(Code.EVIDENCE_LIST_INVALID);
            values.add(text);
        }
        if (values.stream().distinct().count() != values.size()) throw invalid(Code.DUPLICATE_LIST_VALUE);
        return List.copyOf(values);
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    record Permit(
            int requestedCount,
            List<Game> selectedGames,
            List<Game> referenceGames,
            RecommendationShortfall shortfall,
            Map<Integer, Map<String, CandidateObservation>> allowedEvidenceByGame) {
        Permit {
            selectedGames = List.copyOf(selectedGames);
            referenceGames = List.copyOf(referenceGames);
            allowedEvidenceByGame = Map.copyOf(allowedEvidenceByGame);
        }
    }

    final class Session {
        private final Permit permit;
        private final RecommendationAgentState state;
        private final String locale;
        private final Consumer<String> accumulatedAnswerListener;
        private final StringBuilder answer = new StringBuilder();
        private final List<RecommendationReplyPart> replyParts = new ArrayList<>();
        private final Set<String> usedEvidenceIds = new LinkedHashSet<>();
        private final Set<Integer> gamesWithReasons = new LinkedHashSet<>();
        private final Set<Integer> gamesWithTradeoffs = new LinkedHashSet<>();
        private int messageBlocks;
        private boolean finished;

        private Session(
                Permit permit,
                RecommendationAgentState state,
                String locale,
                Consumer<String> accumulatedAnswerListener) {
            this.permit = permit;
            this.state = state;
            this.locale = locale;
            this.accumulatedAnswerListener = accumulatedAnswerListener;
        }

        void acceptBlock(JsonNode block) {
            if (finished) throw new IllegalStateException("recommendation publication is already finished");
            requireObject(block, Set.of("surface", "role", "bggId", "internalEvidenceIds", "text"));
            Surface surface = enumValue(Surface.class, block.path("surface"), Code.SURFACE_INVALID);
            BlockRole role = enumValue(BlockRole.class, block.path("role"), Code.ROLE_INVALID);
            Integer bggId = nullableId(block.path("bggId"));
            int availableEvidenceCount = permit.allowedEvidenceByGame().values().stream()
                    .mapToInt(Map::size)
                    .sum();
            List<String> evidenceIds = strings(
                    block.path("internalEvidenceIds"),
                    0,
                    availableEvidenceCount);
            if (surface == Surface.MESSAGE) {
                acceptMessage(role, bggId, evidenceIds, text(block.path("text"), MAX_MESSAGE_BLOCK_CODE_POINTS));
            } else {
                acceptCard(role, bggId, evidenceIds, block.path("text"));
            }
        }

        ConversationResponse finish() {
            if (finished) throw new IllegalStateException("recommendation publication is already finished");
            finished = true;
            Set<Integer> selectedIds = permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (messageBlocks == 0 || answer.isEmpty()) throw invalid(Code.MESSAGE_REQUIRED);
            if (!gamesWithReasons.containsAll(selectedIds)) throw invalid(Code.CARD_REASON_REQUIRED);

            List<RecommendedGame> games = selector.present(
                    permit.selectedGames(),
                    state.profile,
                    permit.referenceGames(),
                    runtime.chinese(locale),
                    state.research).stream()
                    .map(game -> new RecommendedGame(
                            game.game(),
                            game.matches(),
                            game.tradeoffs(),
                            game.reasons(),
                            game.claims(),
                            replyParts.stream()
                                    .filter(part -> part.claim().bggId()
                                            == game.game().ranking().bggId())
                                    .toList()))
                    .toList();
            String completeAnswer = answer.toString();
            List<String> completedActions = new ArrayList<>(state.actions);
            if (permit.shortfall() != null) completedActions.add("RECOMMENDATION_AVAILABILITY_SHORTFALL");
            completedActions.add("RECOMMEND_GAMES");
            var userModel = evidenceReview.userModelView(state, locale);
            var sources = runtime.responseSources(state, games, usedEvidenceIds);
            ConversationResponse response = new ConversationResponse(
                    Outcome.RECOMMENDATIONS,
                    DecisionMode.MODEL_ASSISTED,
                    completeAnswer,
                    state.profile,
                    null,
                    state.sourceCount,
                    state.verified.size(),
                    userModel,
                    sources,
                    new HarnessTrace(
                            state.modelCalls,
                            state.catalogCalls,
                            state.webResearchCalls,
                            false,
                            completedActions,
                            state.elapsedMs()),
                    games,
                    state.comparison,
                    permit.shortfall(),
                    completeAnswer);

            // Commit only after the whole response projection succeeds. A late source or presentation failure
            // must not leave a half-published turn in the mutable Agent state.
            state.finalResponseGameIds.addAll(selectedIds);
            state.finalResponseEvidenceIds.addAll(usedEvidenceIds);
            state.actions.clear();
            state.actions.addAll(completedActions);
            return response;
        }

        private void acceptMessage(
                BlockRole role,
                Integer bggId,
                List<String> evidenceIds,
                String text) {
            if (role != BlockRole.NARRATIVE) throw invalid(Code.MESSAGE_ROLE_INVALID);
            validateEvidence(bggId, evidenceIds, false);
            int separatorLength = answer.isEmpty() ? 0 : 2;
            int nextLength = answer.codePointCount(0, answer.length())
                    + separatorLength
                    + text.codePointCount(0, text.length());
            if (nextLength > MAX_MESSAGE_CODE_POINTS) throw invalid(Code.MESSAGE_TOO_LONG);
            if (!answer.isEmpty()) answer.append("\n\n");
            answer.append(text);
            messageBlocks++;
            usedEvidenceIds.addAll(evidenceIds);
            accumulatedAnswerListener.accept(answer.toString());
        }

        private void acceptCard(
                BlockRole role,
                Integer bggId,
                List<String> evidenceIds,
                JsonNode textNode) {
            if (role != BlockRole.WHY_FIT && role != BlockRole.TRADEOFF) {
                throw invalid(Code.CARD_ROLE_INVALID);
            }
            if (bggId == null || permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .noneMatch(id -> Objects.equals(id, bggId))) {
                throw invalid(Code.CARD_GAME_INVALID);
            }
            if (evidenceIds.isEmpty()) throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            List<CandidateObservation> evidence = validateEvidence(bggId, evidenceIds, true);
            String text = text(
                    textNode,
                    role == BlockRole.WHY_FIT ? MAX_REASON_CODE_POINTS : MAX_TRADEOFF_CODE_POINTS);
            if (role == BlockRole.WHY_FIT) {
                gamesWithReasons.add(bggId);
            } else if (!gamesWithTradeoffs.add(bggId)) {
                throw invalid(Code.DUPLICATE_TRADEOFF);
            }
            replyParts.add(new RecommendationReplyPart(
                    role == BlockRole.WHY_FIT ? ReplyPartRole.WHY_FIT : ReplyPartRole.TRADEOFF,
                    new CandidateClaim(
                            bggId,
                            "recommendationJudgment",
                            CandidateClaim.Type.PREFERENCE_INFERENCE,
                            null,
                            CandidateClaim.Relation.OBSERVED,
                            text,
                            evidence)));
            usedEvidenceIds.addAll(evidenceIds);
        }

        private List<CandidateObservation> validateEvidence(
                Integer bggId,
                List<String> evidenceIds,
                boolean selectedGameRequired) {
            if (bggId != null) {
                Map<String, CandidateObservation> allowed = permit.allowedEvidenceByGame().get(bggId);
                if (allowed == null || selectedGameRequired && permit.selectedGames().stream()
                        .map(game -> game.ranking().bggId())
                        .noneMatch(id -> Objects.equals(id, bggId))) {
                    throw invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED);
                }
                List<CandidateObservation> values = evidenceIds.stream().map(allowed::get).toList();
                if (values.stream().anyMatch(Objects::isNull)) {
                    throw invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED);
                }
                return values;
            }
            List<CandidateObservation> values = new ArrayList<>();
            for (String evidenceId : evidenceIds) {
                CandidateObservation found = permit.allowedEvidenceByGame().values().stream()
                        .map(evidence -> evidence.get(evidenceId))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED));
                values.add(found);
            }
            return List.copyOf(values);
        }

        private Integer nullableId(JsonNode value) {
            return value.isNull()
                    ? null
                    : integer(value, 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID);
        }

        private String text(JsonNode value, int maximumCodePoints) {
            if (!value.isTextual()) throw invalid(Code.TEXT_INVALID);
            String text = value.asText().strip();
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > maximumCodePoints) throw invalid(Code.TEXT_INVALID);
            return text;
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode value, Code code) {
            if (!value.isTextual()) throw invalid(code);
            try {
                return Enum.valueOf(type, value.asText());
            } catch (IllegalArgumentException exception) {
                throw invalid(code);
            }
        }
    }

    enum Code {
        OBJECT_REQUIRED,
        OBJECT_FIELDS_INVALID,
        PUBLICATION_SEED_INVALID,
        SELECTION_COUNT_INVALID,
        BGG_ID_INVALID,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_IN_PUBLICATION_SEED,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        REFERENCE_ID_NOT_IN_PUBLICATION_SEED,
        REFERENCE_ID_SELECTED,
        REFERENCE_ID_NOT_VERIFIED,
        ID_LIST_INVALID,
        EVIDENCE_LIST_INVALID,
        DUPLICATE_LIST_VALUE,
        SURFACE_INVALID,
        ROLE_INVALID,
        MESSAGE_ROLE_INVALID,
        CARD_ROLE_INVALID,
        CARD_GAME_INVALID,
        BLOCK_EVIDENCE_NOT_GROUNDED,
        TEXT_INVALID,
        MESSAGE_TOO_LONG,
        DUPLICATE_TRADEOFF,
        MESSAGE_REQUIRED,
        CARD_REASON_REQUIRED
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

    private enum Surface {
        MESSAGE,
        CARD
    }

    private enum BlockRole {
        NARRATIVE,
        WHY_FIT,
        TRADEOFF
    }
}
