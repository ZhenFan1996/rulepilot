package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.TeachingUnitInput;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Validates only the teaching granularity that the outline Agent itself committed to. */
final class TeachingPlannedUnitCoveragePolicy {

    private TeachingPlannedUnitCoveragePolicy() {}

    static void validate(
            List<TeachingUnitInput> plannedUnits,
            List<RuleEvidence> evidence,
            SectionDraft draft) {
        Map<String, TeachingUnitInput> unitsById = plannedUnits.stream()
                .collect(Collectors.toMap(
                        TeachingUnitInput::unitId,
                        unit -> unit,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        if (unitsById.size() != plannedUnits.size()) {
            throw new IllegalArgumentException("planned teaching unit IDs must be unique");
        }

        Map<String, List<StepDraft>> stepsByUnit = new LinkedHashMap<>();
        for (StepDraft step : draft.steps()) {
            if (step.teachingUnitIds().size() > 1) {
                throw new IllegalArgumentException(
                        "one teaching step cannot absorb multiple independently planned teaching units");
            }
            for (String unitId : step.teachingUnitIds()) {
                if (!unitsById.containsKey(unitId)) {
                    throw new IllegalArgumentException("teaching step references an unknown planned teaching unit");
                }
                stepsByUnit.computeIfAbsent(unitId, ignored -> new java.util.ArrayList<>()).add(step);
            }
        }
        if (plannedUnits.isEmpty()) {
            if (!stepsByUnit.isEmpty()) {
                throw new IllegalArgumentException("teaching steps cannot invent a plan-owned teaching unit");
            }
            return;
        }

        Map<UUID, RuleEvidence> evidenceById = evidence.stream().collect(Collectors.toMap(
                RuleEvidence::chunkId,
                source -> source,
                (first, duplicate) -> first,
                LinkedHashMap::new));
        for (TeachingUnitInput unit : plannedUnits) {
            List<StepDraft> unitSteps = stepsByUnit.getOrDefault(unit.unitId(), List.of());
            if (unitSteps.isEmpty()) {
                throw new IllegalArgumentException(
                        "teaching draft omitted planned teaching unit " + unit.unitId());
            }
            Set<UUID> directEvidenceIds = unit.directEvidenceIds().stream()
                    .filter(evidenceById::containsKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean taughtWithDirectSource = !directEvidenceIds.isEmpty()
                    && unitSteps.stream().anyMatch(step ->
                            step.citationIds().stream().anyMatch(directEvidenceIds::contains));
            if (!taughtWithDirectSource) {
                throw new MissingDirectUnitEvidenceException(
                        unit.unitId(), String.join(", ", unit.sourceIdentifiers()), directEvidenceIds);
            }
        }

        boolean unknownCitation = draft.steps().stream()
                .flatMap(step -> step.citationIds().stream())
                .anyMatch(citationId -> !evidenceById.containsKey(citationId));
        if (unknownCitation) {
            throw new IllegalArgumentException("teaching step cites evidence outside retrieval scope");
        }
    }

    static boolean containsIdentifier(String text, String identifier) {
        String normalizedText = identity(text);
        String normalizedIdentifier = identity(identifier);
        if (normalizedIdentifier.isBlank()) return false;
        boolean ascii = normalizedIdentifier.codePoints().allMatch(codePoint -> codePoint < 128);
        if (!ascii) return normalizedText.contains(normalizedIdentifier);
        int from = 0;
        while (from <= normalizedText.length() - normalizedIdentifier.length()) {
            int match = normalizedText.indexOf(normalizedIdentifier, from);
            if (match < 0) return false;
            int left = match - 1;
            int right = match + normalizedIdentifier.length();
            boolean leftBoundary = left < 0 || !Character.isLetterOrDigit(normalizedText.charAt(left));
            boolean rightBoundary = right >= normalizedText.length()
                    || !Character.isLetterOrDigit(normalizedText.charAt(right));
            if (leftBoundary && rightBoundary) return true;
            from = match + 1;
        }
        return false;
    }

    private static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    static final class MissingDirectUnitEvidenceException extends IllegalArgumentException {
        private final String unitId;
        private final String sourceIdentifier;
        private final Set<UUID> directEvidenceIds;

        MissingDirectUnitEvidenceException(
                String unitId, String sourceIdentifier, Set<UUID> directEvidenceIds) {
            super("planned teaching unit " + unitId
                    + " must cite direct evidence for source " + sourceIdentifier
                    + ": " + directEvidenceIds);
            this.unitId = unitId;
            this.sourceIdentifier = sourceIdentifier;
            this.directEvidenceIds = Set.copyOf(directEvidenceIds);
        }

        String unitId() {
            return unitId;
        }

        String sourceIdentifier() {
            return sourceIdentifier;
        }

        Set<UUID> directEvidenceIds() {
            return directEvidenceIds;
        }
    }
}
