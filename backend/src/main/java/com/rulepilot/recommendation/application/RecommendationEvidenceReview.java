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

    PreferenceUpdatePlan planPreferenceUpdates(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request) {
        if (!arguments.has("preferenceUpdates")) return PreferenceUpdatePlan.unchanged(current);
        JsonNode updates = arguments.path("preferenceUpdates");
        if (updates.isArray()) {
            return planPreferenceUpdateList(updates, current, request);
        }
        RecommendationProfile candidate = updatedProfile(updates, current, request);
        return new PreferenceUpdatePlan(candidate, Map.of(), !candidate.equals(current), true);
    }

    void commitPreferenceUpdates(PreferenceUpdatePlan plan, RecommendationAgentState state) {
        if (plan == null) throw new IllegalArgumentException("preference update plan is required");
        state.profile = plan.profile();
        state.contextualPreferences.putAll(plan.contextualUpdates());
        if (!plan.contextualUpdates().isEmpty()) state.actions.add("RECORD_CONTEXTUAL_PREFERENCE");
        if (plan.profileUpdated()) {
            state.actions.add("UPDATE_PREFERENCES");
            state.reconsiderSelectionAfterPreferenceUpdate();
        } else if (plan.directUpdatesPresent()) {
            state.actions.add("IGNORED_REDUNDANT_PREFERENCE_UPDATE");
        }
    }

    private PreferenceUpdatePlan planPreferenceUpdateList(
            JsonNode updates,
            RecommendationProfile current,
            ConversationRequest request) {
        if (updates.isEmpty()) return PreferenceUpdatePlan.unchanged(current);
        if (updates.size() > MAX_PROFILE_UPDATES) {
            throw new InvalidAction("TOO_MANY_PREFERENCE_UPDATES");
        }
        ArrayNode directUpdates = json.createArrayNode();
        Map<String, ContextualPreference> contextualUpdates = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode update : updates) {
            PreferenceEvidenceClassification classification = preferenceClassification(update, request);
            String field = text(update.path("field"), 1, 40);
            if (!seen.add(canonicalPreferenceField(field))) {
                throw new InvalidAction("PREFERENCE_FIELD_INVALID");
            }
            if (classification.contextual()) {
                ContextualPreference contextual = contextualPreference(
                        update, request, classification.reason());
                contextualUpdates.put(contextual.field(), contextual);
            } else {
                directUpdates.add(preferencePayload(update));
            }
        }
        RecommendationProfile candidate = directUpdates.isEmpty()
                ? current
                : updatedProfileFromList(directUpdates, current, request);
        return new PreferenceUpdatePlan(
                candidate,
                contextualUpdates,
                !candidate.equals(current),
                !directUpdates.isEmpty());
    }

    private String canonicalPreferenceField(String field) {
        return switch (field) {
            case "players", "playerCount" -> "playerCount";
            case "maxMinutes", "durationMinutes" -> "durationMinutes";
            case "maxWeight", "complexity" -> "complexity";
            default -> field;
        };
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
            requirePreferenceEvidence(update.path("evidence").asText(), request);
            return new PreferenceEvidenceClassification(false, classification);
        }
        if (!"CONTEXTUAL_COMPLETE_GROUP".equals(classification)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        }
        String field = text(update.path("field"), 1, 40);
        JsonNode value = update.path("value");
        boolean exactPlayerCount = ("players".equals(field) || "playerCount".equals(field))
                        && value.isIntegralNumber()
                        && value.canConvertToInt()
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

    private ContextualPreference contextualPreference(
            JsonNode update,
            ConversationRequest request,
            String reason) {
        String evidenceId = text(update.path("evidence"), 1, 160);
        String evidenceText = preferenceEvidence(request).get(evidenceId);
        if (evidenceText == null) throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        String field = text(update.path("field"), 1, 40);
        JsonNode value = update.path("value");
        int players = "players".equals(field)
                ? integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE")
                : value.isIntegralNumber()
                        ? integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE")
                        : integer(value.path("minimum"), 1, 20, "PLAYERS_OUT_OF_RANGE");
        return new ContextualPreference(
                "playerCount",
                Integer.toString(players),
                evidenceId,
                evidenceText,
                reason);
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
            ConstraintRange<Integer> proposed = playerCountConstraint(
                    update.path("value"), evidence, request);
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
                    ConstraintRange<Integer> proposed = playerCountConstraint(value, evidence, request);
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
                minimum, maximum, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
    }

    private ConstraintRange<Integer> playerCountConstraint(
            JsonNode value,
            String evidenceId,
            ConversationRequest request) {
        if (value.isNull()) return null;
        if (value.isIntegralNumber()) {
            return exactIntegerConstraint(
                    integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE"), evidenceId, request);
        }
        return integerConstraintRange(
                value, 1, 20, evidenceId, request, "PLAYERS_OUT_OF_RANGE");
    }

    private ConstraintRange<Integer> durationConstraintRange(
            JsonNode value,
            String evidenceId,
            ConversationRequest request) {
        return integerConstraintRange(
                value, 5, 1_440, evidenceId, request, "DURATION_OUT_OF_RANGE");
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
                minimum, maximum, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
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
                value, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
    }

    private ConstraintRange<Integer> maximumIntegerConstraint(
            int value,
            String evidenceId,
            ConversationRequest request) {
        return ConstraintRange.hard(
                null, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
    }

    private ConstraintRange<BigDecimal> maximumDecimalConstraint(
            BigDecimal value,
            String evidenceId,
            ConversationRequest request) {
        return ConstraintRange.hard(
                null, value, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
    }

    private String preferenceEvidenceText(String evidenceId, ConversationRequest request) {
        String evidence = preferenceEvidence(request).get(evidenceId);
        if (evidence == null) throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        return evidence;
    }

    private int evidenceTurn(String evidenceId, ConversationRequest request) {
        int turn = 0;
        for (String knownEvidenceId : preferenceEvidence(request).keySet()) {
            turn++;
            if (knownEvidenceId.equals(evidenceId)) return turn;
        }
        throw new InvalidAction("PREFERENCE_EVIDENCE_NOT_GROUNDED");
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

    void requireUserEvidence(String evidenceId, ConversationRequest request) {
        requirePreferenceEvidence(evidenceId, request);
    }

    UserEvidence userEvidence(String evidenceId, ConversationRequest request) {
        requirePreferenceEvidence(evidenceId, request);
        return new UserEvidence(
                preferenceEvidenceText(evidenceId, request),
                evidenceTurn(evidenceId, request));
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
                        value.evidenceText()))
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
                    ? "暂按 " + value.value() + " 人理解（你可以随时更正）"
                    : "Working with " + value.value() + " players for now; you can correct this at any time";
        }
        return runtime.chinese(locale)
                ? "暂按“" + value.value() + "”理解（你可以随时更正）"
                : "Working assumption: " + value.value() + "; you can correct this at any time";
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
        String value = node.asText().strip();
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
            return Enum.valueOf(type, node.asText());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAction(code);
        }
    }

    private record PreferenceEvidenceClassification(boolean contextual, String reason) {}

    record UserEvidence(String text, int turn) {}

    record PreferenceUpdatePlan(
            RecommendationProfile profile,
            Map<String, ContextualPreference> contextualUpdates,
            boolean profileUpdated,
            boolean directUpdatesPresent) {
        PreferenceUpdatePlan {
            profile = profile == null ? RecommendationProfile.empty() : profile;
            contextualUpdates = contextualUpdates == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contextualUpdates));
        }

        static PreferenceUpdatePlan unchanged(RecommendationProfile profile) {
            return new PreferenceUpdatePlan(profile, Map.of(), false, false);
        }
    }
}
