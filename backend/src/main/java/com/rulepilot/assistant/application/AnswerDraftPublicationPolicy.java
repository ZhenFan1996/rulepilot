package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;

/** Pure final-draft preparation before a validated answer can be published to a player. */
final class AnswerDraftPublicationPolicy {

    private AnswerDraftPublicationPolicy() {}

    static Preparation prepare(ModelRequest request, ModelDraft draft) {
        ModelDraft prepared = AnswerStructuredDraftPolicy.retainSelected(request, draft).draft();
        var playerFacingFailure = AnswerRepairOutcomePolicy.publicationFailure(request, prepared);
        if (playerFacingFailure.isPresent()) {
            var failure = playerFacingFailure.orElseThrow();
            return new Preparation(null, List.of(), failure.status(), failure.message());
        }
        prepared = AnswerBasisPolicy.classify(request, prepared);
        return Preparation.ready(prepared);
    }

    record Preparation(
            ModelDraft draft, List<AnswerWarning> warnings, AnswerStatus failureStatus, String failureMessage) {

        static Preparation ready(ModelDraft draft) {
            return new Preparation(draft, List.of(), null, null);
        }

        boolean ready() {
            return failureStatus == null;
        }
    }
}
