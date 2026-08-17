package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Requests a bounded model repair only for player-facing protocol leakage. */
final class AnswerPlayerFacingRepairPolicy {

    private AnswerPlayerFacingRepairPolicy() {}

    static List<String> feedbackFor(ModelRequest request, ModelDraft draft) {
        return planFor(request, draft).feedback();
    }

    static RepairPlan planFor(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) return new RepairPlan(Set.of(), List.of());
        List<UUID> evidenceIds = request.evidence().stream()
                .map(com.rulepilot.assistant.RuleAnswerModel.EvidenceInput::chunkId)
                .toList();
        EnumSet<PlayerFacingField> editableFields = EnumSet.noneOf(PlayerFacingField.class);
        List<String> feedback = new ArrayList<>();
        if (containsInternalReference(draft.shortVerdict(), evidenceIds)) {
            editableFields.add(PlayerFacingField.SHORT_VERDICT);
        }
        if (containsInternalReference(draft.explanation(), evidenceIds)) {
            editableFields.add(PlayerFacingField.EXPLANATION);
        }
        if (draft.exceptions().stream().anyMatch(value -> containsInternalReference(value, evidenceIds))) {
            editableFields.add(PlayerFacingField.EXCEPTIONS);
        }
        if (!editableFields.isEmpty()) {
            feedback.add(
                    "PLAYER_FACING_OUTPUT: Remove the supplied full evidence UUIDs from player-facing prose. "
                            + "Preserve ordinary rule identifiers, natural technical terms, and all supported prose; "
                            + "teach the same cited rule directly and preserve citationIds.");
        }
        List<String> citationFeedback = AnswerCitationCoveragePolicy.repairFeedback(request, draft);
        if (!citationFeedback.isEmpty()) {
            editableFields.add(PlayerFacingField.CITATION_IDS);
            feedback.addAll(citationFeedback);
        }
        return new RepairPlan(editableFields, feedback);
    }

    private static boolean containsInternalReference(String value, List<UUID> evidenceIds) {
        return AnswerDraftSafetyPolicy.containsKnownEvidenceReference(value, evidenceIds);
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
