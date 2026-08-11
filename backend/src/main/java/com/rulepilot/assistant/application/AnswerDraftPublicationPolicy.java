package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;

/** Pure final-draft preparation before a validated answer can be published to a player. */
final class AnswerDraftPublicationPolicy {

    private AnswerDraftPublicationPolicy() {}

    static ModelDraft removePeripheralEndgameCitations(ModelRequest request, ModelDraft draft) {
        return draft;
    }

    static Preparation prepare(ModelRequest request, ModelDraft draft) {
        ModelDraft prepared = AnswerBasisPolicy.classify(request, draft);
        prepared = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(prepared);
        prepared = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(prepared);
        return Preparation.ready(prepared);
    }

    record Preparation(
            ModelDraft draft, List<AnswerWarning> warnings, AnswerStatus failureStatus, String failureMessage) {

        static Preparation ready(ModelDraft draft) {
            return new Preparation(draft, List.of(), null, null);
        }

        static Preparation readyWithWarning(ModelDraft draft, AnswerWarning warning) {
            return new Preparation(draft, List.of(warning), null, null);
        }

        static Preparation rejected(String failureMessage) {
            return new Preparation(null, List.of(), AnswerStatus.INVALID_MODEL_OUTPUT, failureMessage);
        }

        boolean ready() {
            return failureStatus == null;
        }
    }
}
