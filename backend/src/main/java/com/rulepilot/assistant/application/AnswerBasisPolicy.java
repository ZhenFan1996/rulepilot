package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.domain.AnswerBasis;

/** Validates the Agent's typed answer basis without rewriting its structured decision. */
final class AnswerBasisPolicy {

    private AnswerBasisPolicy() {}

    static ModelDraft classify(ModelRequest request, ModelDraft draft) {
        if (draft == null || !draft.answerable()) return draft;
        boolean groundedApplication = !draft.calculations().isEmpty()
                || request.answerAid() == AnswerAid.SCOPE && !draft.scopeResolutions().isEmpty();
        AnswerBasis classified = groundedApplication ? AnswerBasis.GROUNDED_APPLICATION : AnswerBasis.DIRECT_RULE;
        if (classified != draft.answerBasis()) {
            throw new IllegalArgumentException("answer basis does not match the typed structured aids");
        }
        return draft;
    }
}
