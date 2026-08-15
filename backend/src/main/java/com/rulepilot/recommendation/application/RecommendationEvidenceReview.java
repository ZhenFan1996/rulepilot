package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.RecommendationReActLoop.MAX_MODEL_CALLS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceEvidenceStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceProposal;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceReviewRequest;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceHypothesisView;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.UserModelView;
import com.rulepilot.recommendation.application.RecommendationActions.InvalidAction;
import com.rulepilot.recommendation.application.RecommendationAgentState.ContextualPreference;
import com.rulepilot.recommendation.application.RecommendationAgentState.PreferenceReviewKey;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reviews and applies only player-grounded recommendation preference evidence. */
final class RecommendationEvidenceReview {

    private static final int MAX_PROFILE_UPDATES = 5;
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "playerCount",
            "durationMinutes",
            "complexity",
            "players",
            "maxMinutes",
            "maxWeight",
            "type",
            "interaction");
    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BoardGameRecommendationModel model;
    private final ObjectMapper json;
    private final RecommendationReActLoop runtime;

    RecommendationEvidenceReview(
            BoardGameRecommendationModel model,
            ObjectMapper json,
            RecommendationReActLoop runtime) {
        this.model = model;
        this.json = json;
        this.runtime = runtime;
    }

    void applyPreferenceUpdates(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request) {
        if (!arguments.has("preferenceUpdates")) return;
        JsonNode updates = arguments.path("preferenceUpdates");
        if (updates.isArray()) {
            applyPreferenceUpdateList(updates, state, request, true);
            return;
        }
        RecommendationProfile current = state.profile;
        PreferenceReviewGate review = reviewPreferenceEvidence(updates, current, request, state);
        try {
            state.profile = updatedProfile(updates, current, request, review);
        } catch (InvalidAction invalid) {
            if (!Set.of(
                            "PREFERENCE_IS_CONTEXTUAL",
                            "PREFERENCE_EVIDENCE_NOT_SUPPORTED",
                            "PREFERENCE_REVIEW_UNAVAILABLE")
                    .contains(invalid.code)) {
                throw invalid;
            }
            if (!"PREFERENCE_IS_CONTEXTUAL".equals(invalid.code)) {
                state.actions.add("REJECTED_PREFERENCE_UPDATE:" + invalid.code);
            }
            return;
        }
        if (state.profile.equals(current)) {
            state.actions.add("IGNORED_REDUNDANT_PREFERENCE_UPDATE");
            return;
        }
        state.actions.add("UPDATE_PREFERENCES");
        state.reconsiderSelectionAfterPreferenceUpdate();
    }

    String applyPreferenceUpdatesForRead(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request) {
        if (!arguments.has("preferenceUpdates")) return "";
        JsonNode updates = arguments.path("preferenceUpdates");
        if (!updates.isArray()) {
            try {
                applyPreferenceUpdates(arguments, state, request);
                return "";
            } catch (InvalidAction invalid) {
                state.actions.add("REJECTED_PREFERENCE_UPDATE:" + invalid.code);
                return invalid.code;
            }
        }
        return applyPreferenceUpdateList(updates, state, request, false);
    }

    private String applyPreferenceUpdateList(
            JsonNode updates,
            RecommendationAgentState state,
            ConversationRequest request,
            boolean strictStructure) {
        if (updates.isEmpty() || updates.size() > MAX_PROFILE_UPDATES) {
            if (strictStructure) throw new InvalidAction("EMPTY_PREFERENCE_UPDATE");
            state.actions.add("REJECTED_PREFERENCE_UPDATE:EMPTY_PREFERENCE_UPDATE");
            return "EMPTY_PREFERENCE_UPDATE";
        }
        if (strictStructure) {
            // Validate the whole shape before committing any field so a malformed sibling cannot leave
            // a partially applied state. Semantic decisions below are intentionally per field.
            updatedProfileFromList(
                    updates,
                    state.profile,
                    request,
                    PreferenceReviewGate.withoutReview());
        }
        boolean updated = false;
        boolean redundant = false;
        Set<String> seen = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        PreferenceReviewGate review = reviewPreferenceEvidence(updates, state.profile, request, state);
        for (JsonNode update : updates) {
            try {
                String field = text(update.path("field"), 1, 40);
                if (!seen.add(field)) throw new InvalidAction("PREFERENCE_FIELD_INVALID");
                RecommendationProfile current = state.profile;
                state.profile = updatedProfileFromList(
                        json.createArrayNode().add(update), current, request, review);
                if (state.profile.equals(current)) {
                    redundant = true;
                } else {
                    updated = true;
                }
            } catch (InvalidAction invalid) {
                if ("PREFERENCE_IS_CONTEXTUAL".equals(invalid.code)) continue;
                if (strictStructure
                        && !Set.of("PREFERENCE_EVIDENCE_NOT_SUPPORTED", "PREFERENCE_REVIEW_UNAVAILABLE")
                                .contains(invalid.code)) {
                    throw invalid;
                }
                if (!warnings.contains(invalid.code)) {
                    warnings.add(invalid.code);
                    state.actions.add("REJECTED_PREFERENCE_UPDATE:" + invalid.code);
                }
            }
        }
        if (updated) {
            state.actions.add("UPDATE_PREFERENCES");
            state.reconsiderSelectionAfterPreferenceUpdate();
        }
        if (redundant) state.actions.add("IGNORED_REDUNDANT_PREFERENCE_UPDATE");
        return String.join(",", warnings);
    }

    private RecommendationProfile updatedProfile(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request,
            PreferenceReviewGate review) {
        if (arguments != null && arguments.isArray()) {
            return updatedProfileFromList(arguments, current, request, review);
        }
        requireObject(arguments, Set.of(), PROFILE_FIELDS);
        if (arguments.isEmpty()) throw new InvalidAction("EMPTY_PREFERENCE_UPDATE");
        ConstraintRange<Integer> playerCount = current.playerCount();
        ConstraintRange<Integer> durationMinutes = current.durationMinutes();
        ConstraintRange<BigDecimal> complexity = current.complexity();
        BggGameType type = current.type();
        InteractionPreference interaction = current.interaction();
        rejectDuplicateRangeForms(arguments, "playerCount", "players");
        rejectDuplicateRangeForms(arguments, "durationMinutes", "maxMinutes");
        rejectDuplicateRangeForms(arguments, "complexity", "maxWeight");
        if (arguments.has("playerCount")) {
            JsonNode update = preference(arguments.path("playerCount"));
            String evidence = text(update.path("evidence"), 1, 160);
            ConstraintRange<Integer> proposed = update.path("value").isNull()
                    ? null
                    : integerConstraintRange(
                            update.path("value"), 1, 20, evidence, request, "PLAYERS_OUT_OF_RANGE");
            if (!sameRange(playerCount, proposed)) {
                requirePreferenceEvidence("playerCount", update.path("value"), evidence, request, review);
                playerCount = proposed;
            }
        }
        if (arguments.has("players")) {
            JsonNode update = preference(arguments.path("players"));
            int players = integer(update.path("value"), 1, 20, "PLAYERS_OUT_OF_RANGE");
            if (playerCount == null || !playerCount.exact() || !Objects.equals(playerCount.minimum(), players)) {
                requirePreferenceEvidence(
                        "players", update.path("value"), text(update.path("evidence"), 1, 160), request, review);
                playerCount = exactIntegerConstraint(players, text(update.path("evidence"), 1, 160), request);
            }
        }
        if (arguments.has("durationMinutes")) {
            JsonNode update = preference(arguments.path("durationMinutes"));
            String evidence = text(update.path("evidence"), 1, 160);
            ConstraintRange<Integer> proposed = update.path("value").isNull()
                    ? null
                    : integerConstraintRange(
                            update.path("value"), 5, 1_440, evidence, request, "DURATION_OUT_OF_RANGE");
            if (!sameRange(durationMinutes, proposed)) {
                requirePreferenceEvidence("durationMinutes", update.path("value"), evidence, request, review);
                durationMinutes = proposed;
            }
        }
        if (arguments.has("maxMinutes")) {
            JsonNode update = preference(arguments.path("maxMinutes"));
            int maxMinutes = integer(update.path("value"), 0, 1_440, "DURATION_OUT_OF_RANGE");
            if (maxMinutes > 0 && maxMinutes < 5) throw new InvalidAction("DURATION_OUT_OF_RANGE");
            ConstraintRange<Integer> proposed = maxMinutes == 0
                    ? null
                    : maximumIntegerConstraint(maxMinutes, text(update.path("evidence"), 1, 160), request);
            if (!sameRange(durationMinutes, proposed)) {
                requirePreferenceEvidence(
                        "maxMinutes", update.path("value"), text(update.path("evidence"), 1, 160), request, review);
                durationMinutes = proposed;
            }
        }
        if (arguments.has("complexity")) {
            JsonNode update = preference(arguments.path("complexity"));
            String evidence = text(update.path("evidence"), 1, 160);
            ConstraintRange<BigDecimal> proposed = update.path("value").isNull()
                    ? null
                    : decimalConstraintRange(
                            update.path("value"), BigDecimal.ZERO, new BigDecimal("5"), evidence, request);
            if (!sameRange(complexity, proposed)) {
                requirePreferenceEvidence("complexity", update.path("value"), evidence, request, review);
                complexity = proposed;
            }
        }
        if (arguments.has("maxWeight")) {
            JsonNode update = preference(arguments.path("maxWeight"));
            if (!update.path("value").isNumber()) throw new InvalidAction("WEIGHT_TYPE");
            BigDecimal maxWeight = update.path("value").decimalValue();
            if (maxWeight.compareTo(BigDecimal.ZERO) < 0 || maxWeight.compareTo(new BigDecimal("5")) > 0) {
                throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
            }
            ConstraintRange<BigDecimal> proposed = maxWeight.compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : maximumDecimalConstraint(maxWeight, text(update.path("evidence"), 1, 160), request);
            if (!sameRange(complexity, proposed)) {
                requirePreferenceEvidence(
                        "maxWeight", update.path("value"), text(update.path("evidence"), 1, 160), request, review);
                complexity = proposed;
            }
        }
        if (arguments.has("type")) {
            JsonNode update = preference(arguments.path("type"));
            BggGameType value = enumValue(
                    BggGameType.class, update.path("value"), "GAME_TYPE_INVALID");
            if (current.type() != value) {
                requirePreferenceEvidence(
                        "type", update.path("value"), text(update.path("evidence"), 1, 160), request, review);
            }
            type = value;
        }
        if (arguments.has("interaction")) {
            JsonNode update = preference(arguments.path("interaction"));
            InteractionPreference value = enumValue(
                    InteractionPreference.class, update.path("value"), "INTERACTION_INVALID");
            if (current.interaction() != value) {
                requirePreferenceEvidence(
                        "interaction", update.path("value"), text(update.path("evidence"), 1, 160), request, review);
            }
            interaction = value;
        }
        return new RecommendationProfile(playerCount, durationMinutes, complexity, type, interaction);
    }

    private RecommendationProfile updatedProfileFromList(
            JsonNode updates,
            RecommendationProfile current,
            ConversationRequest request,
            PreferenceReviewGate review) {
        if (updates.isEmpty() || updates.size() > MAX_PROFILE_UPDATES) {
            throw new InvalidAction("EMPTY_PREFERENCE_UPDATE");
        }
        RecommendationProfile result = current;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode update : updates) {
            requireObject(update, Set.of("field", "value", "evidence"), Set.of());
            String field = text(update.path("field"), 1, 40);
            if (!PROFILE_FIELDS.contains(field) || !seen.add(field)) {
                throw new InvalidAction("PREFERENCE_FIELD_INVALID");
            }
            String evidence = text(update.path("evidence"), 1, 160);
            JsonNode value = update.path("value");
            result = switch (field) {
                case "playerCount" -> {
                    ConstraintRange<Integer> proposed = value.isNull()
                            ? null
                            : integerConstraintRange(
                                    value, 1, 20, evidence, request, "PLAYERS_OUT_OF_RANGE");
                    if (!sameRange(result.playerCount(), proposed)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            sameRange(result.playerCount(), proposed) ? result.playerCount() : proposed,
                            result.durationMinutes(), result.complexity(), result.type(), result.interaction());
                }
                case "players" -> {
                    int players = integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE");
                    if (result.playerCount() == null
                            || !result.playerCount().exact()
                            || !Objects.equals(result.playerCount().minimum(), players)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount() != null
                                            && result.playerCount().exact()
                                            && Objects.equals(result.playerCount().minimum(), players)
                                    ? result.playerCount()
                                    : exactIntegerConstraint(players, evidence, request),
                            result.durationMinutes(), result.complexity(), result.type(), result.interaction());
                }
                case "durationMinutes" -> {
                    ConstraintRange<Integer> proposed = value.isNull()
                            ? null
                            : integerConstraintRange(
                                    value, 5, 1_440, evidence, request, "DURATION_OUT_OF_RANGE");
                    if (!sameRange(result.durationMinutes(), proposed)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(),
                            sameRange(result.durationMinutes(), proposed) ? result.durationMinutes() : proposed,
                            result.complexity(), result.type(), result.interaction());
                }
                case "maxMinutes" -> {
                    int minutes = integer(value, 0, 1_440, "DURATION_OUT_OF_RANGE");
                    if (minutes > 0 && minutes < 5) throw new InvalidAction("DURATION_OUT_OF_RANGE");
                    ConstraintRange<Integer> proposed = minutes == 0
                            ? null
                            : maximumIntegerConstraint(minutes, evidence, request);
                    if (!sameRange(result.durationMinutes(), proposed)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(),
                            sameRange(result.durationMinutes(), proposed) ? result.durationMinutes() : proposed,
                            result.complexity(), result.type(), result.interaction());
                }
                case "complexity" -> {
                    ConstraintRange<BigDecimal> proposed = value.isNull()
                            ? null
                            : decimalConstraintRange(
                                    value, BigDecimal.ZERO, new BigDecimal("5"), evidence, request);
                    if (!sameRange(result.complexity(), proposed)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(), result.durationMinutes(),
                            sameRange(result.complexity(), proposed) ? result.complexity() : proposed,
                            result.type(), result.interaction());
                }
                case "maxWeight" -> {
                    if (!value.isNumber()) throw new InvalidAction("WEIGHT_TYPE");
                    BigDecimal weight = value.decimalValue();
                    if (weight.compareTo(BigDecimal.ZERO) < 0
                            || weight.compareTo(new BigDecimal("5")) > 0) {
                        throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
                    }
                    ConstraintRange<BigDecimal> proposed = weight.compareTo(BigDecimal.ZERO) == 0
                            ? null
                            : maximumDecimalConstraint(weight, evidence, request);
                    if (!sameRange(result.complexity(), proposed)) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(), result.durationMinutes(),
                            sameRange(result.complexity(), proposed) ? result.complexity() : proposed,
                            result.type(), result.interaction());
                }
                case "type" -> {
                    BggGameType preference = enumValue(
                            BggGameType.class, value, "GAME_TYPE_INVALID");
                    if (result.type() != preference) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(), result.durationMinutes(), result.complexity(),
                            preference, result.interaction());
                }
                case "interaction" -> {
                    InteractionPreference preference = enumValue(
                            InteractionPreference.class, value, "INTERACTION_INVALID");
                    if (result.interaction() != preference) {
                        requirePreferenceEvidence(field, value, evidence, request, review);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(), result.durationMinutes(), result.complexity(), result.type(), preference);
                }
                default -> throw new InvalidAction("PREFERENCE_FIELD_INVALID");
            };
        }
        return result;
    }

    private JsonNode preference(JsonNode value) {
        requireObject(value, Set.of("value", "evidence"), Set.of());
        text(value.path("evidence"), 1, 160);
        return value;
    }

    private void rejectDuplicateRangeForms(JsonNode updates, String rangeField, String legacyField) {
        if (updates.has(rangeField) && updates.has(legacyField)) {
            throw new InvalidAction("DUPLICATE_PREFERENCE_RANGE");
        }
    }

    private ConstraintRange<Integer> integerConstraintRange(
            JsonNode value,
            int allowedMinimum,
            int allowedMaximum,
            String evidenceId,
            ConversationRequest request,
            String errorCode) {
        requireObject(value, Set.of(), Set.of("minimum", "maximum"));
        Integer minimum = nullableInteger(value.path("minimum"), allowedMinimum, allowedMaximum, errorCode);
        Integer maximum = nullableInteger(value.path("maximum"), allowedMinimum, allowedMaximum, errorCode);
        if (minimum == null && maximum == null || minimum != null && maximum != null && minimum > maximum) {
            throw new InvalidAction(errorCode);
        }
        return ConstraintRange.hard(
                minimum, maximum, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId));
    }

    private ConstraintRange<BigDecimal> decimalConstraintRange(
            JsonNode value,
            BigDecimal allowedMinimum,
            BigDecimal allowedMaximum,
            String evidenceId,
            ConversationRequest request) {
        requireObject(value, Set.of(), Set.of("minimum", "maximum"));
        BigDecimal minimum = nullableDecimal(value.path("minimum"), allowedMinimum, allowedMaximum);
        BigDecimal maximum = nullableDecimal(value.path("maximum"), allowedMinimum, allowedMaximum);
        if (minimum == null && maximum == null
                || minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
        }
        return ConstraintRange.hard(
                minimum, maximum, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId));
    }

    private Integer nullableInteger(JsonNode value, int minimum, int maximum, String errorCode) {
        return value.isMissingNode() || value.isNull() ? null : integer(value, minimum, maximum, errorCode);
    }

    private BigDecimal nullableDecimal(JsonNode value, BigDecimal minimum, BigDecimal maximum) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw new InvalidAction("WEIGHT_TYPE");
        BigDecimal decimal = value.decimalValue();
        if (decimal.compareTo(minimum) < 0 || decimal.compareTo(maximum) > 0) {
            throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
        }
        return decimal;
    }

    private ConstraintRange<Integer> exactIntegerConstraint(
            int value,
            String evidenceId,
            ConversationRequest request) {
        return ConstraintRange.hard(
                value, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId));
    }

    private ConstraintRange<Integer> maximumIntegerConstraint(
            int value,
            String evidenceId,
            ConversationRequest request) {
        return ConstraintRange.hard(
                null, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId));
    }

    private ConstraintRange<BigDecimal> maximumDecimalConstraint(
            BigDecimal value,
            String evidenceId,
            ConversationRequest request) {
        return ConstraintRange.hard(
                null, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId));
    }

    private String preferenceEvidenceText(String evidenceId, ConversationRequest request) {
        String evidence = preferenceEvidence(request).get(evidenceId);
        if (evidence == null) throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        return bounded(evidence, 160);
    }

    private int evidenceTurn(String evidenceId) {
        if (evidenceId == null || !evidenceId.matches("U[1-9][0-9]*")) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        }
        try {
            return Integer.parseInt(evidenceId.substring(1));
        } catch (NumberFormatException exception) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        }
    }

    private <T extends Comparable<? super T>> boolean sameRange(
            ConstraintRange<T> current,
            ConstraintRange<T> proposed) {
        if (current == proposed) return true;
        if (current == null || proposed == null) return false;
        return Objects.equals(current.minimum(), proposed.minimum())
                && Objects.equals(current.maximum(), proposed.maximum())
                && current.strength() == proposed.strength();
    }

    private boolean sameWeight(BigDecimal current, BigDecimal proposed) {
        return current != null && proposed != null && current.compareTo(proposed) == 0;
    }

    private PreferenceReviewGate reviewPreferenceEvidence(
            JsonNode updates,
            RecommendationProfile current,
            ConversationRequest request,
            RecommendationAgentState state) {
        List<PreferenceReviewKey> allProposed = proposedPreferenceChanges(updates, current, request);
        if (allProposed.isEmpty()) return PreferenceReviewGate.withoutReview();
        Set<PreferenceReviewKey> existingContextual = state.contextualPreferences.values().stream()
                .map(value -> new PreferenceReviewKey(value.field(), value.value(), value.evidenceId()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<PreferenceReviewKey> proposed = allProposed.stream()
                .filter(item -> !existingContextual.contains(item))
                .filter(item -> !state.rejectedPreferenceUpdates.contains(item))
                .toList();
        if (proposed.isEmpty()) {
            return new PreferenceReviewGate(Set.of(), existingContextual, false, false);
        }
        if (!model.preferenceReviewConfigured(state.modelConfigurationOwner)) {
            return PreferenceReviewGate.withoutReview();
        }
        if (state.modelCalls >= MAX_MODEL_CALLS) {
            state.actions.add("PREFERENCE_REVIEW_UNAVAILABLE");
            return PreferenceReviewGate.reviewFailed();
        }
        List<PreferenceEvidence> evidence = preferenceEvidence(request).entrySet().stream()
                .map(entry -> new PreferenceEvidence(entry.getKey(), entry.getValue()))
                .toList();
        List<PreferenceProposal> proposals = java.util.stream.IntStream.range(0, proposed.size())
                .mapToObj(index -> {
                    PreferenceReviewKey item = proposed.get(index);
                    return new PreferenceProposal(index, item.field(), item.value(), item.evidenceId());
                })
                .toList();
        try {
            state.modelCalls++;
            var review = runtime.withinDeadline(
                    state,
                    () -> model.reviewPreferences(
                            new PreferenceReviewRequest(evidence, proposals),
                            state.modelConfigurationOwner));
            if (review.decisions().size() != proposals.size()) {
                throw new IllegalStateException("preference review decision count does not match proposals");
            }
            Set<PreferenceReviewKey> direct = java.util.stream.IntStream.range(0, proposed.size())
                    .filter(index -> review.decisions().get(index).status() == PreferenceEvidenceStatus.DIRECT)
                    .mapToObj(proposed::get)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<PreferenceReviewKey> contextual = java.util.stream.IntStream.range(0, proposed.size())
                    .filter(index -> review.decisions().get(index).status() == PreferenceEvidenceStatus.CONTEXTUAL)
                    .mapToObj(proposed::get)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (int index = 0; index < proposed.size(); index++) {
                PreferenceReviewKey item = proposed.get(index);
                if (review.decisions().get(index).status() == PreferenceEvidenceStatus.UNSUPPORTED) {
                    state.rejectedPreferenceUpdates.add(item);
                    continue;
                }
                if (review.decisions().get(index).status() != PreferenceEvidenceStatus.CONTEXTUAL) continue;
                state.contextualPreferences.put(
                        item.field(),
                        new ContextualPreference(
                                item.field(),
                                item.value(),
                                item.evidenceId(),
                                preferenceEvidence(request).get(item.evidenceId()),
                                review.decisions().get(index).reason()));
            }
            state.actions.add("REVIEW_PREFERENCE_EVIDENCE");
            if (!contextual.isEmpty()) state.actions.add("RECORD_CONTEXTUAL_PREFERENCE");
            Set<PreferenceReviewKey> allContextual = new LinkedHashSet<>(existingContextual);
            allContextual.addAll(contextual);
            return new PreferenceReviewGate(direct, allContextual, false, false);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Recommendation preference evidence review failed ({})",
                    exception.getClass().getSimpleName());
            state.actions.add("PREFERENCE_REVIEW_UNAVAILABLE");
            return PreferenceReviewGate.reviewFailed();
        }
    }

    private List<PreferenceReviewKey> proposedPreferenceChanges(
            JsonNode updates,
            RecommendationProfile current,
            ConversationRequest request) {
        Map<String, String> evidence = preferenceEvidence(request);
        if (updates == null || updates.isNull()) return List.of();
        List<PreferenceReviewKey> proposed = new ArrayList<>();
        if (updates.isArray()) {
            for (JsonNode update : updates) {
                if (!update.isObject()) continue;
                addPreferenceReviewKey(
                        proposed,
                        update.path("field").asText(""),
                        update.path("value"),
                        update.path("evidence").asText(""),
                        current,
                        evidence);
            }
        } else if (updates.isObject()) {
            for (String field : PROFILE_FIELDS) {
                if (!updates.has(field) || !updates.path(field).isObject()) continue;
                JsonNode update = updates.path(field);
                addPreferenceReviewKey(
                        proposed,
                        field,
                        update.path("value"),
                        update.path("evidence").asText(""),
                        current,
                        evidence);
            }
        }
        return proposed.stream().distinct().limit(MAX_PROFILE_UPDATES).toList();
    }

    private void addPreferenceReviewKey(
            List<PreferenceReviewKey> proposed,
            String field,
            JsonNode value,
            String evidenceId,
            RecommendationProfile current,
            Map<String, String> evidence) {
        if (!PROFILE_FIELDS.contains(field)
                || evidenceId.isBlank()
                || !evidence.containsKey(evidenceId)
                || !preferenceMayChange(field, value, current)) {
            return;
        }
        proposed.add(new PreferenceReviewKey(field, canonicalPreferenceValue(value), evidenceId));
    }

    private boolean preferenceMayChange(
            String field,
            JsonNode value,
            RecommendationProfile current) {
        return switch (field) {
            case "playerCount" -> !canonicalRange(current.playerCount()).equals(canonicalPreferenceValue(value));
            case "players" -> !value.canConvertToInt()
                    || !Objects.equals(current.players(), value.intValue());
            case "durationMinutes" -> !canonicalRange(current.durationMinutes()).equals(canonicalPreferenceValue(value));
            case "maxMinutes" -> !value.canConvertToInt()
                    || !Objects.equals(current.maxMinutes(), value.intValue());
            case "complexity" -> !canonicalRange(current.complexity()).equals(canonicalPreferenceValue(value));
            case "maxWeight" -> !value.isNumber()
                    || !sameWeight(current.maxWeight(), value.decimalValue());
            case "type" -> !value.isTextual()
                    || !current.type().name().equals(value.textValue());
            case "interaction" -> !value.isTextual()
                    || !current.interaction().name().equals(value.textValue());
            default -> false;
        };
    }

    private String canonicalPreferenceValue(JsonNode value) {
        if (value == null || value.isNull()) return "*..*";
        if (value != null && value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (value != null && value.isObject()) {
            return canonicalBound(value.path("minimum")) + ".." + canonicalBound(value.path("maximum"));
        }
        return value.asText("").strip();
    }

    private String canonicalBound(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "*";
        return value.isNumber()
                ? value.decimalValue().stripTrailingZeros().toPlainString()
                : "?";
    }

    private String canonicalRange(ConstraintRange<? extends Number> range) {
        if (range == null) return "*..*";
        return canonicalNumber(range.minimum()) + ".." + canonicalNumber(range.maximum());
    }

    private String canonicalNumber(Number value) {
        if (value == null) return "*";
        return value instanceof BigDecimal decimal
                ? decimal.stripTrailingZeros().toPlainString()
                : value.toString();
    }

    private void requirePreferenceEvidence(
            String field,
            JsonNode value,
            String evidenceId,
            ConversationRequest request,
            PreferenceReviewGate review) {
        if (!preferenceEvidence(request).containsKey(evidenceId)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        }
        PreferenceReviewKey key = new PreferenceReviewKey(field, canonicalPreferenceValue(value), evidenceId);
        if (!review.bypass() && review.contextual().contains(key)) {
            throw new InvalidAction("PREFERENCE_IS_CONTEXTUAL");
        }
        if (review.unavailable()) {
            throw new InvalidAction("PREFERENCE_REVIEW_UNAVAILABLE");
        }
        if (!review.bypass() && !review.direct().contains(key)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_SUPPORTED");
        }
    }

    Map<String, String> preferenceEvidence(ConversationRequest request) {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (DialogueMessage message : request.transcript()) {
            if ("user".equals(message.role())) {
                evidence.put("U" + (evidence.size() + 1), message.text());
            }
        }
        return evidence;
    }

    private String confirmedPreferenceValue(RecommendationProfile profile, String field) {
        return switch (field) {
            case "playerCount" -> profile.playerCount() == null ? null : canonicalRange(profile.playerCount());
            case "players" -> profile.players() == null ? null : profile.players().toString();
            case "durationMinutes" -> profile.durationMinutes() == null
                    ? null
                    : canonicalRange(profile.durationMinutes());
            case "maxMinutes" -> profile.maxMinutes() == null ? null : profile.maxMinutes().toString();
            case "complexity" -> profile.complexity() == null ? null : canonicalRange(profile.complexity());
            case "maxWeight" -> profile.maxWeight() == null
                    ? null
                    : profile.maxWeight().stripTrailingZeros().toPlainString();
            case "type" -> profile.type() == BggGameType.ALL ? null : profile.type().name();
            case "interaction" -> profile.interaction() == InteractionPreference.ANY
                    ? null
                    : profile.interaction().name();
            default -> null;
        };
    }

    Map<String, Object> profileForAgent(RecommendationProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("playerCount", profile.playerCount());
        value.put("durationMinutes", profile.durationMinutes());
        value.put("complexity", profile.complexity());
        value.put("type", profile.type());
        value.put("interaction", profile.interaction());
        return value;
    }

    private String profileSummary(RecommendationProfile profile, String locale) {
        List<String> values = new ArrayList<>();
        if (profile.playerCount() != null) {
            values.add(integerConstraintText(profile.playerCount(), locale, "人", "players"));
        }
        if (profile.durationMinutes() != null) {
            values.add(integerConstraintText(profile.durationMinutes(), locale, "分钟", "minutes"));
        }
        if (profile.complexity() != null) {
            values.add((runtime.chinese(locale) ? "复杂度 " : "complexity ") + decimalConstraintText(profile.complexity()));
        }
        if (profile.type() != BggGameType.ALL) values.add(profile.type().name());
        if (profile.interaction() != InteractionPreference.ANY) values.add(profile.interaction().name());
        if (values.isEmpty()) return "";
        return (runtime.chinese(locale) ? "已明确记录：" : "Explicitly saved: ")
                + String.join(runtime.chinese(locale) ? "、" : ", ", values);
    }

    private String integerConstraintText(
            ConstraintRange<Integer> range,
            String locale,
            String chineseUnit,
            String englishUnit) {
        boolean zh = runtime.chinese(locale);
        if (range.minimum() != null && range.maximum() != null) {
            String value = range.minimum().equals(range.maximum())
                    ? range.minimum().toString()
                    : range.minimum() + "–" + range.maximum();
            return value + (zh ? " " + chineseUnit : " " + englishUnit);
        }
        return range.minimum() != null
                ? (zh ? "至少 " : "at least ") + range.minimum() + " " + (zh ? chineseUnit : englishUnit)
                : (zh ? "最多 " : "up to ") + range.maximum() + " " + (zh ? chineseUnit : englishUnit);
    }

    private String decimalConstraintText(ConstraintRange<BigDecimal> range) {
        if (range.minimum() != null && range.maximum() != null) {
            if (range.minimum().compareTo(range.maximum()) == 0) {
                return range.minimum().stripTrailingZeros().toPlainString();
            }
            return range.minimum().stripTrailingZeros().toPlainString()
                    + "–"
                    + range.maximum().stripTrailingZeros().toPlainString();
        }
        return range.minimum() != null
                ? "≥ " + range.minimum().stripTrailingZeros().toPlainString()
                : "≤ " + range.maximum().stripTrailingZeros().toPlainString();
    }

    String constraintLabel(RecommendationProfile profile, String subject, String locale) {
        return switch (subject) {
            case "playerCount" -> integerConstraintText(profile.playerCount(), locale, "人", "players");
            case "durationMinutes" -> integerConstraintText(profile.durationMinutes(), locale, "分钟", "minutes");
            case "complexity" -> (runtime.chinese(locale) ? "复杂度 " : "complexity ")
                    + decimalConstraintText(profile.complexity());
            case "bggType" -> (runtime.chinese(locale) ? "游戏类型 " : "game type ") + profile.type().name();
            case "mechanics" -> (runtime.chinese(locale) ? "互动方式 " : "interaction mode ")
                    + profile.interaction().name();
            default -> throw new InvalidAction("NO_MATCH_RELAXATION_NOT_ACTIONABLE");
        };
    }

    UserModelView userModelView(RecommendationAgentState state, String locale) {
        List<PreferenceHypothesisView> hypotheses = state.contextualPreferences.values().stream()
                .map(value -> new PreferenceHypothesisView(
                        value.field(),
                        value.value(),
                        contextualPreferenceLabel(value, locale),
                        "medium",
                        bounded(value.evidenceText(), 160)))
                .toList();
        String confirmed = profileSummary(state.profile, locale);
        if (hypotheses.isEmpty()) return new UserModelView(confirmed, List.of());
        String assumptionSummary = runtime.chinese(locale)
                ? "正在使用可随时更正的语境假设"
                : "Using a contextual assumption that you can correct at any time";
        return new UserModelView(
                confirmed.isBlank() ? assumptionSummary : confirmed + (runtime.chinese(locale) ? "；" : "; ") + assumptionSummary,
                hypotheses);
    }

    private String contextualPreferenceLabel(ContextualPreference value, String locale) {
        if ("players".equals(value.field())) {
            return runtime.chinese(locale)
                    ? "暂按 " + value.value() + " 人理解（尚未确认为硬条件）"
                    : "Working with " + value.value() + " players for now (not a confirmed hard constraint)";
        }
        return runtime.chinese(locale)
                ? "暂按“" + value.value() + "”理解（尚未确认为硬条件）"
                : "Working assumption: " + value.value() + " (not a confirmed hard constraint)";
    }

    private void requireObject(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw new InvalidAction("ARGUMENT_OBJECT_REQUIRED");
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new InvalidAction("UNEXPECTED_ARGUMENT");
        if (required.stream().anyMatch(field -> !node.has(field))) {
            throw new InvalidAction("REQUIRED_ARGUMENT_MISSING");
        }
    }

    private String text(JsonNode node, int minimum, int maximum) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText().strip().replaceAll("\\s+", " ");
        if (value.length() < minimum || value.length() > maximum) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private int integer(JsonNode node, int minimum, int maximum, String code) {
        if (!node.canConvertToInt()) throw new InvalidAction(code);
        int value = node.intValue();
        if (value < minimum || value > maximum) throw new InvalidAction(code);
        return value;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String code) {
        if (!node.isTextual()) throw new InvalidAction(code);
        try {
            String token = Normalizer.normalize(node.asText(), Normalizer.Form.NFKC)
                    .strip()
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                    .replaceAll("[-\\s]+", "_")
                    .toUpperCase(Locale.ROOT);
            return Enum.valueOf(type, token);
        } catch (IllegalArgumentException exception) {
            throw new InvalidAction(code);
        }
    }

    private String bounded(String value, int maximum) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return checked.length() <= maximum ? checked : checked.substring(0, maximum);
    }

    private record PreferenceReviewGate(
            Set<PreferenceReviewKey> direct,
            Set<PreferenceReviewKey> contextual,
            boolean bypass,
            boolean unavailable) {

        private PreferenceReviewGate {
            direct = Set.copyOf(direct);
            contextual = Set.copyOf(contextual);
        }

        private static PreferenceReviewGate withoutReview() {
            return new PreferenceReviewGate(Set.of(), Set.of(), true, false);
        }

        private static PreferenceReviewGate reviewFailed() {
            return new PreferenceReviewGate(Set.of(), Set.of(), false, true);
        }
    }
}
