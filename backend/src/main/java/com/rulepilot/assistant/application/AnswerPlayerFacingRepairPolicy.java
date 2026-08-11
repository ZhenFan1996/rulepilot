package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;

/** Requests a bounded model repair only for player-facing protocol leakage. */
final class AnswerPlayerFacingRepairPolicy {

    private AnswerPlayerFacingRepairPolicy() {}

    static List<String> feedbackFor(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null || !AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
            return List.of();
        }
        return List.of(
                "PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                        + "and other internal references. Teach the same cited rule directly; preserve citationIds.");
    }
}
