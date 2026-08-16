package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;

/** Assigns the application-owned answer basis without inferring table state from natural-language wording. */
final class AnswerBasisPolicy {

    private AnswerBasisPolicy() {}

    static ModelDraft classify(ModelRequest request, ModelDraft draft) {
        if (draft == null || !draft.answerable()) return draft;
        boolean groundedApplication = !draft.calculations().isEmpty()
                || request.answerAid() == AnswerAid.SCOPE && !draft.scopeResolutions().isEmpty();
        String classified = groundedApplication ? "GROUNDED_APPLICATION" : "DIRECT_RULE";
        if (classified.equalsIgnoreCase(draft.answerBasis())) return draft;
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                draft.citationIds(),
                draft.exceptions(),
                draft.confidence(),
                classified,
                draft.calculations(),
                draft.situationChecks(),
                draft.walkthroughSteps(),
                draft.decisionBranches(),
                draft.exceptionClauses(),
                draft.termDefinitions(), draft.workedExamples(), draft.priorityResolutions(), draft.timingResolutions(),
                draft.tieResolutions(), draft.scopeResolutions(), draft.conceptComparisons(), draft.ruleOptions());
    }
}
