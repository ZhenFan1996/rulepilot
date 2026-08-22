package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;

/** Keeps only the structured aid selected by the accepted question plan. */
final class AnswerStructuredDraftPolicy {

    private AnswerStructuredDraftPolicy() {}

    static Selection retainSelected(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("structured draft input is invalid");
        AnswerAid selected = request.answerAid();
        boolean omitted = selected != AnswerAid.CALCULATION && !draft.calculations().isEmpty()
                || selected != AnswerAid.WALKTHROUGH && !draft.walkthroughSteps().isEmpty()
                || selected != AnswerAid.DECISION_TABLE && !draft.decisionBranches().isEmpty()
                || selected != AnswerAid.EXCEPTIONS && !draft.exceptionClauses().isEmpty()
                || selected != AnswerAid.DEFINITIONS && !draft.termDefinitions().isEmpty()
                || selected != AnswerAid.EXAMPLE && !draft.workedExamples().isEmpty()
                || selected != AnswerAid.RULE_PRIORITY && !draft.priorityResolutions().isEmpty()
                || selected != AnswerAid.TIMING && !draft.timingResolutions().isEmpty()
                || selected != AnswerAid.TIE && !draft.tieResolutions().isEmpty()
                || selected != AnswerAid.SCOPE && !draft.scopeResolutions().isEmpty()
                || selected != AnswerAid.CONCEPT_COMPARISON && !draft.conceptComparisons().isEmpty()
                || selected != AnswerAid.OPTIONS && !draft.ruleOptions().isEmpty();
        if (!omitted) return new Selection(draft, false);
        return new Selection(new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                draft.citationIds(),
                draft.exceptions(),
                draft.confidence(),
                draft.answerBasis(),
                selected == AnswerAid.CALCULATION ? draft.calculations() : List.of(),
                selected == AnswerAid.WALKTHROUGH ? draft.walkthroughSteps() : List.of(),
                selected == AnswerAid.DECISION_TABLE ? draft.decisionBranches() : List.of(),
                selected == AnswerAid.EXCEPTIONS ? draft.exceptionClauses() : List.of(),
                selected == AnswerAid.DEFINITIONS ? draft.termDefinitions() : List.of(),
                selected == AnswerAid.EXAMPLE ? draft.workedExamples() : List.of(),
                selected == AnswerAid.RULE_PRIORITY ? draft.priorityResolutions() : List.of(),
                selected == AnswerAid.TIMING ? draft.timingResolutions() : List.of(),
                selected == AnswerAid.TIE ? draft.tieResolutions() : List.of(),
                selected == AnswerAid.SCOPE ? draft.scopeResolutions() : List.of(),
                selected == AnswerAid.CONCEPT_COMPARISON ? draft.conceptComparisons() : List.of(),
                selected == AnswerAid.OPTIONS ? draft.ruleOptions() : List.of()), true);
    }

    record Selection(ModelDraft draft, boolean omittedUnselectedDetails) {}
}
