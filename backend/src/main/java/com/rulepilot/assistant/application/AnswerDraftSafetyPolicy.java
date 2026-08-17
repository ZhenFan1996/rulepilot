package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Detects concrete identities from the active evidence set without classifying natural prose. */
final class AnswerDraftSafetyPolicy {

    private AnswerDraftSafetyPolicy() {}

    static boolean containsInternalEvidenceReference(ModelDraft draft, Collection<UUID> evidenceIds) {
        return draft != null && containsKnownEvidenceReference(playerFacingText(draft), evidenceIds);
    }

    static boolean containsInternalCoreReference(ModelDraft draft, Collection<UUID> evidenceIds) {
        if (draft == null) return false;
        return containsKnownEvidenceReference(draft.shortVerdict(), evidenceIds)
                || containsKnownEvidenceReference(draft.explanation(), evidenceIds)
                || draft.exceptions().stream()
                        .anyMatch(value -> containsKnownEvidenceReference(value, evidenceIds));
    }

    static boolean containsKnownEvidenceReference(String value, Collection<UUID> evidenceIds) {
        if (value == null || value.isBlank() || evidenceIds == null || evidenceIds.isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return evidenceIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .map(id -> id.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private static String playerFacingText(ModelDraft draft) {
        List<String> text = new ArrayList<>();
        text.add(draft.shortVerdict());
        text.add(draft.explanation());
        text.addAll(draft.exceptions());
        draft.calculations().forEach(value -> {
            if (value != null) text.add(value.expression());
        });
        draft.situationChecks().forEach(value -> {
            if (value != null) {
                text.add(value.requirement());
                text.add(value.playerFact());
            }
        });
        draft.walkthroughSteps().forEach(value -> {
            if (value != null) {
                text.add(value.instruction());
                text.add(value.explanation());
            }
        });
        draft.decisionBranches().forEach(value -> {
            if (value != null) {
                text.add(value.condition());
                text.add(value.outcome());
            }
        });
        draft.exceptionClauses().forEach(value -> {
            if (value != null) {
                text.add(value.condition());
                text.add(value.effect());
            }
        });
        draft.termDefinitions().forEach(value -> {
            if (value != null) {
                text.add(value.term());
                text.add(value.definition());
                text.add(value.boundary());
            }
        });
        draft.workedExamples().forEach(value -> {
            if (value != null) {
                text.add(value.setup());
                text.add(value.action());
                text.add(value.outcome());
            }
        });
        draft.priorityResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.baseRule());
                text.add(value.competingRule());
                text.add(value.resolution());
            }
        });
        draft.timingResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.timingContext());
                text.add(value.resolutionOrder());
                text.add(value.orderSource());
            }
        });
        draft.tieResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.tieContext());
                if (value.resolutionSteps() != null) text.addAll(value.resolutionSteps());
                text.add(value.finalOutcome());
            }
        });
        draft.scopeResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.ruleContext());
                text.add(value.governingCondition());
                text.add(value.currentSituation());
                text.add(value.effect());
            }
        });
        draft.conceptComparisons().forEach(value -> {
            if (value != null) {
                text.add(value.leftConcept());
                text.add(value.leftDefinition());
                text.add(value.rightConcept());
                text.add(value.rightDefinition());
                text.add(value.commonGround());
                text.add(value.keyDifference());
                text.add(value.practicalBoundary());
            }
        });
        draft.ruleOptions().forEach(value -> {
            if (value != null) {
                text.add(value.decisionContext());
                text.add(value.selectionRule());
                text.add(value.optionName());
                text.add(value.availabilityCondition());
                text.add(value.result());
            }
        });
        return text.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
    }
}
