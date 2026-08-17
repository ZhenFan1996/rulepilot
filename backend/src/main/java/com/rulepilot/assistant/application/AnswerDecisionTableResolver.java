package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.DecisionBranchRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected decision table. */
final class AnswerDecisionTableResolver {

    List<RuleDecisionBranch> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("decision table input is invalid");
        List<DecisionBranchRequest> proposed = draft.decisionBranches();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.DECISION_TABLE, proposed.isEmpty(), "decision table");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> conditions = new LinkedHashSet<>();
        return proposed.stream()
                .map(branch -> resolveOne(request, draft, branch))
                .peek(branch -> {
                    if (!conditions.add(AnswerStructuredAidPolicy.identityKey(branch.condition()))) {
                        throw new IllegalArgumentException("duplicate decision branch condition");
                    }
                })
                .toList();
    }

    boolean requiresDecisionTable(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.DECISION_TABLE);
    }

    private RuleDecisionBranch resolveOne(ModelRequest modelRequest, ModelDraft draft, DecisionBranchRequest item) {
        if (item == null) throw new IllegalArgumentException("decision branch is null");
        return new RuleDecisionBranch(
                AnswerStructuredAidPolicy.requiredText(item.condition(), "decision branch condition"),
                AnswerStructuredAidPolicy.requiredText(item.outcome(), "decision branch outcome"),
                AnswerStructuredAidPolicy.enumValue(item.basis(), DecisionBranchBasis.class, "decision branch basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "decision branch"));
    }
}
