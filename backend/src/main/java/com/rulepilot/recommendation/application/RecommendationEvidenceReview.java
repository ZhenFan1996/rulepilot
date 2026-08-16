package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceHypothesisView;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.UserModelView;
import com.rulepilot.recommendation.application.RecommendationActions.InvalidAction;
import com.rulepilot.recommendation.application.RecommendationAgentState.ContextualPreference;
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

/** Applies typed preference updates only after deterministic value and user-evidence validation. */
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
    private final ObjectMapper json;
    private final RecommendationReActLoop runtime;

    RecommendationEvidenceReview(ObjectMapper json, RecommendationReActLoop runtime) {
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
        try {
            state.profile = updatedProfile(updates, current, request);
        } catch (InvalidAction invalid) {
            if (!"PREFERENCE_IS_CONTEXTUAL".equals(invalid.code)) {
                throw invalid;
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
        if (updates.isEmpty()) return "";
        if (updates.size() > MAX_PROFILE_UPDATES) {
            if (strictStructure) throw new InvalidAction("TOO_MANY_PREFERENCE_UPDATES");
            state.actions.add("REJECTED_PREFERENCE_UPDATE:TOO_MANY_PREFERENCE_UPDATES");
            return "TOO_MANY_PREFERENCE_UPDATES";
        }
        if (strictStructure) {
            // Validate the whole shape before committing any field so a malformed sibling cannot leave
            // a partially applied state. Semantic decisions below are intentionally per field.
            updatedProfileFromList(
                    preferencePayloads(updates),
                    state.profile,
                    request);
            for (JsonNode update : updates) {
                preferenceClassification(update, request);
            }
        }
        boolean updated = false;
        boolean redundant = false;
        Set<String> seen = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (JsonNode update : updates) {
            try {
                PreferenceEvidenceClassification classification = preferenceClassification(update, request);
                String field = text(update.path("field"), 1, 40);
                if (!seen.add(field)) throw new InvalidAction("PREFERENCE_FIELD_INVALID");
                if (classification.contextual()) {
                    recordContextualPreference(update, state, request, classification.reason());
                    continue;
                }
                RecommendationProfile current = state.profile;
                state.profile = updatedProfileFromList(
                        json.createArrayNode().add(preferencePayload(update)),
                        current,
                        request);
                if (state.profile.equals(current)) {
                    redundant = true;
                } else {
                    updated = true;
                }
            } catch (InvalidAction invalid) {
                if ("PREFERENCE_IS_CONTEXTUAL".equals(invalid.code)) continue;
                if (strictStructure) {
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

    private ArrayNode preferencePayloads(JsonNode updates) {
        ArrayNode payloads = json.createArrayNode();
        for (JsonNode update : updates) {
            requireObject(
                    update,
                    Set.of("field", "value", "evidence"),
                    Set.of("evidenceClassification"));
            payloads.add(preferencePayload(update));
        }
        return payloads;
    }

    private ObjectNode preferencePayload(JsonNode update) {
        ObjectNode payload = json.createObjectNode();
        payload.set("field", update.path("field"));
        payload.set("value", update.path("value"));
        payload.set("evidence", update.path("evidence"));
        return payload;
    }

    private PreferenceEvidenceClassification preferenceClassification(
            JsonNode update,
            ConversationRequest request) {
        requireObject(
                update,
                Set.of("field", "value", "evidence"),
                Set.of("evidenceClassification"));
        String classification = update.has("evidenceClassification")
                ? text(update.path("evidenceClassification"), 1, 40)
                : "DIRECT";
        if ("DIRECT".equals(classification)) {
            return new PreferenceEvidenceClassification(false, classification);
        }
        if (request != null && directlyStatesNumericPreference(update, request)) {
            return new PreferenceEvidenceClassification(false, "DIRECT");
        }
        if (!"CONTEXTUAL_COMPLETE_GROUP".equals(classification)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        }
        String field = text(update.path("field"), 1, 40);
        JsonNode value = update.path("value");
        boolean exactPlayerCount = "players".equals(field) && value.canConvertToInt()
                || "playerCount".equals(field)
                        && value.isObject()
                        && value.path("minimum").canConvertToInt()
                        && value.path("maximum").canConvertToInt()
                        && value.path("minimum").intValue() == value.path("maximum").intValue();
        if (!exactPlayerCount) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        }
        if (request != null && !preferenceEvidence(request).containsKey(update.path("evidence").asText())) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        }
        return new PreferenceEvidenceClassification(true, classification);
    }

    private boolean directlyStatesNumericPreference(JsonNode update, ConversationRequest request) {
        String evidence = preferenceEvidence(request).get(update.path("evidence").asText());
        if (evidence == null) return false;
        List<String> numbers = numericTokens(evidence);
        String field = update.path("field").asText();
        JsonNode value = update.path("value");
        return switch (field) {
            case "players", "maxMinutes" -> value.canConvertToInt()
                    && containsInteger(numbers, value.intValue());
            case "playerCount" -> value.isObject()
                    && containsIntegerBounds(numbers, value);
            case "durationMinutes" -> value.isObject()
                    && (containsIntegerBounds(numbers, value)
                            || isOpenDurationLowerBoundSentinel(value, numbers));
            case "maxWeight" -> value.isNumber()
                    && containsDecimal(numbers, value.decimalValue());
            case "complexity" -> value.isObject()
                    && containsDecimalBounds(numbers, value);
            default -> false;
        };
    }

    private List<String> numericTokens(String text) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean decimalPointSeen = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isDigit(character)) {
                current.append(character);
                continue;
            }
            if ((character == '.' || character == '．')
                    && !decimalPointSeen
                    && !current.isEmpty()
                    && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index + 1))) {
                current.append('.');
                decimalPointSeen = true;
                continue;
            }
            if (!current.isEmpty()) {
                values.add(current.toString());
                current.setLength(0);
                decimalPointSeen = false;
            }
        }
        if (!current.isEmpty()) values.add(current.toString());
        return List.copyOf(values);
    }

    private boolean containsIntegerBounds(List<String> numbers, JsonNode range) {
        JsonNode minimum = range.path("minimum");
        JsonNode maximum = range.path("maximum");
        return (!minimum.isNumber() || containsInteger(numbers, minimum.intValue()))
                && (!maximum.isNumber() || containsInteger(numbers, maximum.intValue()))
                && (minimum.isNumber() || maximum.isNumber());
    }

    private boolean containsDecimalBounds(List<String> numbers, JsonNode range) {
        JsonNode minimum = range.path("minimum");
        JsonNode maximum = range.path("maximum");
        return (!minimum.isNumber() || containsDecimal(numbers, minimum.decimalValue()))
                && (!maximum.isNumber() || containsDecimal(numbers, maximum.decimalValue()))
                && (minimum.isNumber() || maximum.isNumber());
    }

    private boolean containsInteger(List<String> numbers, int expected) {
        String canonical = Integer.toString(expected);
        return numbers.stream().anyMatch(value -> !value.contains(".") && canonical.equals(value));
    }

    private boolean containsDecimal(List<String> numbers, BigDecimal expected) {
        return numbers.stream().anyMatch(value -> {
            try {
                return new BigDecimal(value).compareTo(expected) == 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        });
    }

    private void recordContextualPreference(
            JsonNode update,
            RecommendationAgentState state,
            ConversationRequest request,
            String reason) {
        String evidenceId = text(update.path("evidence"), 1, 160);
        String evidenceText = preferenceEvidence(request).get(evidenceId);
        if (evidenceText == null) throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        String field = text(update.path("field"), 1, 40);
        JsonNode value = update.path("value");
        int players = "players".equals(field)
                ? integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE")
                : integer(value.path("minimum"), 1, 20, "PLAYERS_OUT_OF_RANGE");
        String contextualField = "players".equals(field) ? field : "playerCount";
        state.contextualPreferences.put(
                contextualField,
                new ContextualPreference(
                        contextualField,
                        Integer.toString(players),
                        evidenceId,
                        evidenceText,
                        reason));
        state.actions.add("RECORD_CONTEXTUAL_PREFERENCE");
    }

    private RecommendationProfile updatedProfile(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request) {
        if (arguments != null && arguments.isArray()) {
            return updatedProfileFromList(arguments, current, request);
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
                requirePreferenceEvidence(evidence, request);
                playerCount = proposed;
            }
        }
        if (arguments.has("players")) {
            JsonNode update = preference(arguments.path("players"));
            int players = integer(update.path("value"), 1, 20, "PLAYERS_OUT_OF_RANGE");
            if (playerCount == null || !playerCount.exact() || !Objects.equals(playerCount.minimum(), players)) {
                requirePreferenceEvidence(text(update.path("evidence"), 1, 160), request);
                playerCount = exactIntegerConstraint(players, text(update.path("evidence"), 1, 160), request);
            }
        }
        if (arguments.has("durationMinutes")) {
            JsonNode update = preference(arguments.path("durationMinutes"));
            String evidence = text(update.path("evidence"), 1, 160);
            ConstraintRange<Integer> proposed = update.path("value").isNull()
                    ? null
                    : durationConstraintRange(update.path("value"), evidence, request);
            if (!sameRange(durationMinutes, proposed)) {
                requirePreferenceEvidence(evidence, request);
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
                requirePreferenceEvidence(text(update.path("evidence"), 1, 160), request);
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
                requirePreferenceEvidence(evidence, request);
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
                requirePreferenceEvidence(text(update.path("evidence"), 1, 160), request);
                complexity = proposed;
            }
        }
        if (arguments.has("type")) {
            JsonNode update = preference(arguments.path("type"));
            BggGameType value = enumValue(
                    BggGameType.class, update.path("value"), "GAME_TYPE_INVALID");
            if (current.type() != value) {
                requirePreferenceEvidence(text(update.path("evidence"), 1, 160), request);
            }
            type = value;
        }
        if (arguments.has("interaction")) {
            JsonNode update = preference(arguments.path("interaction"));
            InteractionPreference value = enumValue(
                    InteractionPreference.class, update.path("value"), "INTERACTION_INVALID");
            if (current.interaction() != value) {
                requirePreferenceEvidence(text(update.path("evidence"), 1, 160), request);
            }
            interaction = value;
        }
        return new RecommendationProfile(playerCount, durationMinutes, complexity, type, interaction);
    }

    private RecommendationProfile updatedProfileFromList(
            JsonNode updates,
            RecommendationProfile current,
            ConversationRequest request) {
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
                        requirePreferenceEvidence(evidence, request);
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
                        requirePreferenceEvidence(evidence, request);
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
                            : durationConstraintRange(value, evidence, request);
                    if (!sameRange(result.durationMinutes(), proposed)) {
                        requirePreferenceEvidence(evidence, request);
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
                        requirePreferenceEvidence(evidence, request);
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
                        requirePreferenceEvidence(evidence, request);
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
                        requirePreferenceEvidence(evidence, request);
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
                        requirePreferenceEvidence(evidence, request);
                    }
                    yield new RecommendationProfile(
                            result.playerCount(), result.durationMinutes(), result.complexity(),
                            preference, result.interaction());
                }
                case "interaction" -> {
                    InteractionPreference preference = enumValue(
                            InteractionPreference.class, value, "INTERACTION_INVALID");
                    if (result.interaction() != preference) {
                        requirePreferenceEvidence(evidence, request);
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

    private ConstraintRange<Integer> durationConstraintRange(
            JsonNode value,
            String evidenceId,
            ConversationRequest request) {
        requireObject(value, Set.of(), Set.of("minimum", "maximum"));
        String sourceText = preferenceEvidence(request).get(evidenceId);
        if (sourceText != null
                && isOpenDurationLowerBoundSentinel(value, numericTokens(sourceText))) {
            int maximum = integer(value.path("maximum"), 5, 1_440, "DURATION_OUT_OF_RANGE");
            return ConstraintRange.hard(
                    null, maximum, bounded(sourceText, 160), evidenceTurn(evidenceId));
        }
        return integerConstraintRange(
                value, 5, 1_440, evidenceId, request, "DURATION_OUT_OF_RANGE");
    }

    private boolean isOpenDurationLowerBoundSentinel(JsonNode range, List<String> evidenceNumbers) {
        JsonNode minimum = range.path("minimum");
        JsonNode maximum = range.path("maximum");
        if (!minimum.isNumber()
                || minimum.decimalValue().compareTo(BigDecimal.ZERO) != 0
                || !maximum.canConvertToInt()) {
            return false;
        }
        int ceiling = maximum.intValue();
        return ceiling >= 5
                && ceiling <= 1_440
                && containsInteger(evidenceNumbers, ceiling)
                && !containsInteger(evidenceNumbers, 0);
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

    private void requirePreferenceEvidence(String evidenceId, ConversationRequest request) {
        if (!preferenceEvidence(request).containsKey(evidenceId)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
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
        if ("players".equals(value.field()) || "playerCount".equals(value.field())) {
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

    private record PreferenceEvidenceClassification(boolean contextual, String reason) {}
}
