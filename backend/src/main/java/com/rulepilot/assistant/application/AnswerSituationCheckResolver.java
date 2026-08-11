package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import java.util.List;

/** Rejects model-invented live table state; the answer may use only facts already present in the request. */
final class AnswerSituationCheckResolver {

    List<RuleSituationCheck> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("situation check input is invalid");
        if (!draft.situationChecks().isEmpty()) {
            throw new IllegalArgumentException("model-generated situation checks are not accepted");
        }
        return List.of();
    }
}
