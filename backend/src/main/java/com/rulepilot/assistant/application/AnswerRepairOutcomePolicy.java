package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import java.util.List;
import java.util.Optional;

/** Pure outcomes for the bounded player-facing protocol repair. */
final class AnswerRepairOutcomePolicy {

    private AnswerRepairOutcomePolicy() {}

    static String insufficientRepairMessage(List<String> playerFacingRepair) {
        return "回答修订后仍无法通过发布校验。";
    }

    static Optional<PublicationFailure> publicationFailure(ModelRequest request, ModelDraft draft) {
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。"));
        }
        return Optional.empty();
    }

    record PublicationFailure(AnswerStatus status, String message) {}
}
