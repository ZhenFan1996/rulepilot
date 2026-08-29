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
            "type",
            "interaction");
    private static final Set<String> CLARIFICATION_PROFILE_FIELDS = Set.of(
            "playerCount",
            "durationMinutes",
            "complexity");
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
        return planPreferenceUpdates(arguments, current, request, Set.of(
                "playerCount",
                "durationMinutes",
                "complexity",
                "type",
                "interaction"), PreferenceUpdateMode.DEDICATED);
    }

    PreferenceUpdatePlan planOptionalPreferenceUpdates(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request) {
        return planPreferenceUpdates(arguments, current, request, Set.of(
                "playerCount",
                "durationMinutes",
                "complexity",
                "type",
                "interaction"), PreferenceUpdateMode.ATTACHED);
    }

    PreferenceUpdatePlan planClarificationPreferenceUpdates(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request) {
        return planPreferenceUpdates(
                arguments,
                current,
                request,
                CLARIFICATION_PROFILE_FIELDS,
                PreferenceUpdateMode.CLARIFICATION);
    }

    private PreferenceUpdatePlan planPreferenceUpdates(
            JsonNode arguments,
            RecommendationProfile current,
            ConversationRequest request,
            Set<String> allowedFields,
            PreferenceUpdateMode mode) {
        if (!arguments.has("preferenceUpdates")) return PreferenceUpdatePlan.unchanged(current);
        NormalizedPreferenceUpdates normalized = normalizedPreferenceUpdates(arguments.path("preferenceUpdates"));
        if (mode == PreferenceUpdateMode.DEDICATED && !normalized.ignoredFailureCodes().isEmpty()) {
            throw new InvalidAction(normalized.ignoredFailureCodes().getFirst());
        }
        PreferenceUpdatePlan plan = planPreferenceUpdateList(
                normalized.updates(),
                current,
                request,
                allowedFields,
                normalized.ignoredFailureCodes(),
                mode);
        if (mode == PreferenceUpdateMode.DEDICATED && !plan.hasAcceptedUpdate()) {
            throw new InvalidAction(plan.ignoredFailureCodes().isEmpty()
                    ? "EMPTY_PREFERENCE_UPDATE"
                    : plan.ignoredFailureCodes().getFirst());
        }
        return plan;
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
        plan.ignoredFailureCodes().stream()
                .distinct()
                .forEach(code -> state.actions.add("IGNORED_INVALID_PREFERENCE_UPDATE:" + code));
    }

    private PreferenceUpdatePlan planPreferenceUpdateList(
            ArrayNode updates,
            RecommendationProfile current,
            ConversationRequest request,
            Set<String> allowedFields,
            List<String> normalizationFailures,
            PreferenceUpdateMode mode) {
        List<String> ignoredFailures = new ArrayList<>(normalizationFailures);
        if (updates.isEmpty()) {
            return new PreferenceUpdatePlan(current, Map.of(), false, false, ignoredFailures);
        }
        RecommendationProfile candidate = current;
        Map<String, ContextualPreference> contextualUpdates = new LinkedHashMap<>();
        boolean directUpdatesPresent = false;
        List<PreparedPreferenceUpdate> prepared = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonNode update : updates) {
            try {
                String field = text(update.path("field"), 1, 40);
                if (!PROFILE_FIELDS.contains(field)) {
                    throw new InvalidAction("PREFERENCE_FIELD_INVALID");
                }
                prepared.add(new PreparedPreferenceUpdate(update, field));
                occurrences.merge(field, 1, Integer::sum);
            } catch (InvalidAction failure) {
                if (mode == PreferenceUpdateMode.DEDICATED) throw failure;
                ignoredFailures.add(failure.code);
            }
        }
        Set<String> rejectedDuplicates = new LinkedHashSet<>();
        for (PreparedPreferenceUpdate preparedUpdate : prepared) {
            String field = preparedUpdate.field();
            if (occurrences.getOrDefault(field, 0) > 1) {
                if (rejectedDuplicates.add(field)) {
                    boolean directHardDuplicate = prepared.stream()
                            .filter(entry -> field.equals(entry.field()))
                            .anyMatch(entry -> directHardUpdate(
                                    entry.update(), entry.field()));
                    boolean rejectDuplicate = mode == PreferenceUpdateMode.DEDICATED
                            || mode == PreferenceUpdateMode.CLARIFICATION && allowedFields.contains(field);
                    if (directHardDuplicate && rejectDuplicate) {
                        throw new InvalidAction("PREFERENCE_FIELD_INVALID");
                    }
                    ignoredFailures.add("PREFERENCE_FIELD_INVALID");
                }
                continue;
            }
            if (!allowedFields.contains(field)) {
                ignoredFailures.add("PREFERENCE_FIELD_NOT_ALLOWED_FOR_ACTION");
                continue;
            }
            JsonNode update = preparedUpdate.update();
            try {
                if (redundantCategoricalClear(update, field, candidate, request)) {
                    directUpdatesPresent = true;
                    continue;
                }
                PreferenceEvidenceClassification classification = preferenceClassification(update, request);
                if (classification.contextual()) {
                    ContextualPreference contextual = contextualPreference(
                            update, request, classification.reason());
                    contextualUpdates.put(contextual.field(), contextual);
                } else {
                    ArrayNode singleUpdate = json.createArrayNode().add(preferencePayload(update));
                    RecommendationProfile updated =
                            updatedProfileFromList(singleUpdate, candidate, request);
                    directUpdatesPresent = true;
                    candidate = updated;
                }
            } catch (InvalidAction failure) {
                if (mode != PreferenceUpdateMode.ATTACHED && directHardUpdate(update, field)) throw failure;
                ignoredFailures.add(failure.code);
            }
        }
        return new PreferenceUpdatePlan(
                candidate,
                contextualUpdates,
                !candidate.equals(current),
                directUpdatesPresent,
                ignoredFailures);
    }

    private boolean directHardUpdate(JsonNode update, String field) {
        if (!PROFILE_FIELDS.contains(field)) return false;
        JsonNode classification = update.path("evidenceClassification");
        return !classification.isTextual()
                || !"INFERRED_GROUP_MEMBER_COUNT".equals(classification.asText());
    }

    private NormalizedPreferenceUpdates normalizedPreferenceUpdates(JsonNode supplied) {
        ArrayNode normalized = json.createArrayNode();
        List<String> ignoredFailures = new ArrayList<>();
        if (supplied.isArray()) {
            if (supplied.size() > MAX_PROFILE_UPDATES) {
                ignoredFailures.add("TOO_MANY_PREFERENCE_UPDATES");
            }
            supplied.elements().forEachRemaining(update -> {
                if (normalized.size() < MAX_PROFILE_UPDATES) normalized.add(update);
            });
            return new NormalizedPreferenceUpdates(normalized, ignoredFailures);
        }
        if (!supplied.isObject()) {
            ignoredFailures.add("PREFERENCE_UPDATE_OBJECT_REQUIRED");
            return new NormalizedPreferenceUpdates(normalized, ignoredFailures);
        }

        Set<String> patchFields = new LinkedHashSet<>();
        supplied.fieldNames().forEachRemaining(patchFields::add);
        Set<String> allowedPatchFields = new LinkedHashSet<>(PROFILE_FIELDS);
        allowedPatchFields.add("evidence");
        allowedPatchFields.add("evidenceClassification");
        if (patchFields.stream().anyMatch(field -> !allowedPatchFields.contains(field))) {
            ignoredFailures.add("UNEXPECTED_PREFERENCE_FIELD");
        }
        for (String field : List.of(
                "playerCount",
                "durationMinutes",
                "complexity",
                "type",
                "interaction")) {
            if (!supplied.has(field) || normalized.size() == MAX_PROFILE_UPDATES) continue;
            ObjectNode update = json.createObjectNode();
            update.put("field", field);
            update.set("value", supplied.path(field));
            update.set("evidence", supplied.path("evidence"));
            if (supplied.has("evidenceClassification")) {
                update.set("evidenceClassification", supplied.path("evidenceClassification"));
            }
            normalized.add(update);
        }
        if (normalized.isEmpty()) ignoredFailures.add("EMPTY_PREFERENCE_UPDATE");
        return new NormalizedPreferenceUpdates(normalized, ignoredFailures);
    }

    private boolean redundantCategoricalClear(
            JsonNode update,
            String field,
            RecommendationProfile current,
            ConversationRequest request) {
        if (!update.path("value").isNull()
                || !"type".equals(field) && !"interaction".equals(field)
                || "type".equals(field) && current.type() != BggGameType.ALL
                || "interaction".equals(field) && current.interaction() != InteractionPreference.ANY) {
            return false;
        }
        requireObject(
                update,
                Set.of("field", "value", "evidence"),
                Set.of("evidenceClassification"));
        String classification = update.has("evidenceClassification")
                ? text(update.path("evidenceClassification"), 1, 40)
                : "DIRECT";
        if (!"DIRECT".equals(classification)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        }
        requirePreferenceEvidence(update.path("evidence").asText(), request);
        return true;
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
        String field = text(update.path("field"), 1, 40);
        if (Set.of("type", "interaction").contains(field)) {
            if (!"DIRECT".equals(classification)) {
                throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
            }
            requirePreferenceEvidence(update.path("evidence").asText(), request);
            return new PreferenceEvidenceClassification(true, "REVERSIBLE_CATEGORICAL_PREFERENCE");
        }
        if ("DIRECT".equals(classification)) {
            requirePreferenceEvidence(update.path("evidence").asText(), request);
            return new PreferenceEvidenceClassification(false, classification);
        }
        if (!"INFERRED_GROUP_MEMBER_COUNT".equals(classification)) {
            throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        }
        JsonNode value = update.path("value");
        boolean exactPlayerCount = "playerCount".equals(field)
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
        String contextualValue = switch (field) {
            case "playerCount" -> Integer.toString(value.isIntegralNumber()
                    ? integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE")
                    : integer(value.path("minimum"), 1, 20, "PLAYERS_OUT_OF_RANGE"));
            case "type" -> enumValue(BggGameType.class, value, "GAME_TYPE_INVALID").name();
            case "interaction" -> enumValue(
                            InteractionPreference.class, value, "INTERACTION_INVALID")
                    .name();
            default -> throw new InvalidAction("PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID");
        };
        return new ContextualPreference(
                field,
                contextualValue,
                evidenceId,
                evidenceText,
                reason);
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
                    ConstraintRange<Integer> proposed =
                            playerCountConstraint(value, result.playerCount(), evidence, request);
                    if (!sameRange(result.playerCount(), proposed)) {
                        requirePreferenceEvidence(evidence, request);
                    }
                    yield new RecommendationProfile(
                            sameRange(result.playerCount(), proposed) ? result.playerCount() : proposed,
                            result.durationMinutes(), result.complexity(), result.type(), result.interaction());
                }
                case "durationMinutes" -> {
                    ConstraintRange<Integer> proposed = value.isNull()
                            ? null
                            : durationConstraintRange(value, result.durationMinutes(), evidence, request);
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
                                    value,
                                    result.complexity(),
                                    BigDecimal.ZERO,
                                    new BigDecimal("5"),
                                    evidence,
                                    request);
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

    private ConstraintRange<Integer> integerConstraintRange(
            JsonNode value,
            ConstraintRange<Integer> current,
            int allowedMinimum,
            int allowedMaximum,
            String evidenceId,
            ConversationRequest request,
            String errorCode) {
        requireObject(value, Set.of(), Set.of("minimum", "maximum"));
        if (!value.has("minimum") && !value.has("maximum")) {
            throw new InvalidAction(errorCode);
        }
        Integer minimum = value.has("minimum")
                ? nullableInteger(value.path("minimum"), allowedMinimum, allowedMaximum, errorCode)
                : current == null ? null : current.minimum();
        Integer maximum = value.has("maximum")
                ? nullableInteger(value.path("maximum"), allowedMinimum, allowedMaximum, errorCode)
                : current == null ? null : current.maximum();
        if (minimum == null && maximum == null || minimum != null && maximum != null && minimum > maximum) {
            throw new InvalidAction(errorCode);
        }
        return ConstraintRange.hard(
                minimum, maximum, preferenceEvidenceText(evidenceId, request), evidenceTurn(evidenceId, request));
    }

    private ConstraintRange<Integer> playerCountConstraint(
            JsonNode value,
            ConstraintRange<Integer> current,
            String evidenceId,
            ConversationRequest request) {
        if (value.isNull()) return null;
        if (value.isIntegralNumber()) {
            return exactIntegerConstraint(
                    integer(value, 1, 20, "PLAYERS_OUT_OF_RANGE"), evidenceId, request);
        }
        return integerConstraintRange(
                value, current, 1, 20, evidenceId, request, "PLAYERS_OUT_OF_RANGE");
    }

    private ConstraintRange<Integer> durationConstraintRange(
            JsonNode value,
            ConstraintRange<Integer> current,
            String evidenceId,
            ConversationRequest request) {
        return integerConstraintRange(
                value, current, 5, 1_440, evidenceId, request, "DURATION_OUT_OF_RANGE");
    }

    private ConstraintRange<BigDecimal> decimalConstraintRange(
            JsonNode value,
            ConstraintRange<BigDecimal> current,
            BigDecimal allowedMinimum,
            BigDecimal allowedMaximum,
            String evidenceId,
            ConversationRequest request) {
        requireObject(value, Set.of(), Set.of("minimum", "maximum"));
        if (!value.has("minimum") && !value.has("maximum")) {
            throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
        }
        BigDecimal minimum = value.has("minimum")
                ? nullableDecimal(value.path("minimum"), allowedMinimum, allowedMaximum)
                : current == null ? null : current.minimum();
        BigDecimal maximum = value.has("maximum")
                ? nullableDecimal(value.path("maximum"), allowedMinimum, allowedMaximum)
                : current == null ? null : current.maximum();
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

    void requireCurrentTurnUserEvidence(String evidenceId, ConversationRequest request) {
        requireCurrentTurnUserEvidence(evidenceId, request, "REQUESTED_COUNT_EVIDENCE_NOT_CURRENT");
    }

    void requireCurrentTurnUserEvidence(
            String evidenceId,
            ConversationRequest request,
            String failureCode) {
        List<String> evidenceIds = preferenceEvidence(request).keySet().stream().toList();
        if (evidenceIds.isEmpty() || !evidenceIds.getLast().equals(evidenceId)) {
            throw new InvalidAction(failureCode);
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
        if ("playerCount".equals(value.field())) {
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
        List<String> unexpected = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unexpected.add(field);
        });
        if (!unexpected.isEmpty()) throw InvalidAction.unexpectedArguments(unexpected, allowed);
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

    record PreferenceUpdatePlan(
            RecommendationProfile profile,
            Map<String, ContextualPreference> contextualUpdates,
            boolean profileUpdated,
            boolean directUpdatesPresent,
            List<String> ignoredFailureCodes) {
        PreferenceUpdatePlan {
            profile = profile == null ? RecommendationProfile.empty() : profile;
            contextualUpdates = contextualUpdates == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contextualUpdates));
            ignoredFailureCodes = ignoredFailureCodes == null
                    ? List.of()
                    : List.copyOf(ignoredFailureCodes);
        }

        static PreferenceUpdatePlan unchanged(RecommendationProfile profile) {
            return new PreferenceUpdatePlan(profile, Map.of(), false, false, List.of());
        }

        boolean hasAcceptedUpdate() {
            return profileUpdated || directUpdatesPresent || !contextualUpdates.isEmpty();
        }
    }

    private enum PreferenceUpdateMode {
        DEDICATED,
        CLARIFICATION,
        ATTACHED
    }

    private record NormalizedPreferenceUpdates(
            ArrayNode updates,
            List<String> ignoredFailureCodes) {}

    private record PreparedPreferenceUpdate(JsonNode update, String field) {}
}
