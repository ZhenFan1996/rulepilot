package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Requests a bounded model repair only for player-facing protocol leakage. */
final class AnswerPlayerFacingRepairPolicy {

    private AnswerPlayerFacingRepairPolicy() {}

    static List<String> feedbackFor(ModelRequest request, ModelDraft draft) {
        return planFor(request, draft).feedback();
    }

    static RepairPlan planFor(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) return new RepairPlan(Set.of(), List.of());
        EnumSet<PlayerFacingField> editableFields = EnumSet.noneOf(PlayerFacingField.class);
        List<String> feedback = new ArrayList<>();
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft.shortVerdict())) {
            editableFields.add(PlayerFacingField.SHORT_VERDICT);
        }
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft.explanation())) {
            editableFields.add(PlayerFacingField.EXPLANATION);
        }
        if (draft.exceptions().stream().anyMatch(AnswerDraftSafetyPolicy::containsInternalEvidenceReference)) {
            editableFields.add(PlayerFacingField.EXCEPTIONS);
        }
        if (!editableFields.isEmpty()) {
            feedback.add(
                    "PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                            + "and other internal references. Teach the same cited rule directly; preserve citationIds.");
        }
        EnumSet<PlayerFacingField> sourceScopeFields = EnumSet.noneOf(PlayerFacingField.class);
        if (AnswerSourceScopeRepairPolicy.requiresRepair(draft.shortVerdict())) {
            sourceScopeFields.add(PlayerFacingField.SHORT_VERDICT);
        }
        if (AnswerSourceScopeRepairPolicy.requiresRepair(draft.explanation())) {
            sourceScopeFields.add(PlayerFacingField.EXPLANATION);
        }
        if (draft.exceptions().stream().anyMatch(AnswerSourceScopeRepairPolicy::requiresRepair)) {
            sourceScopeFields.add(PlayerFacingField.EXCEPTIONS);
        }
        if (!sourceScopeFields.isEmpty()) {
            editableFields.addAll(sourceScopeFields);
            feedback.addAll(AnswerSourceScopeRepairPolicy.feedbackFor(request, draft));
        }
        return new RepairPlan(editableFields, feedback);
    }

    record RepairPlan(Set<PlayerFacingField> editableFields, List<String> feedback) {
        RepairPlan {
            editableFields = Set.copyOf(editableFields);
            feedback = List.copyOf(feedback);
        }

        boolean required() {
            return !editableFields.isEmpty();
        }
    }
}
