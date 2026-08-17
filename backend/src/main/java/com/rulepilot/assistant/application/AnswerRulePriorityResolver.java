package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RulePriorityRequest;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.RulePriorityResolution;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected rule-priority aid. */
final class AnswerRulePriorityResolver {

    List<RulePriorityResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("rule priority input is invalid");
        List<RulePriorityRequest> proposed = draft.priorityResolutions();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.RULE_PRIORITY, proposed.isEmpty(), "rule priority resolutions");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> pairs = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    String identity = AnswerStructuredAidPolicy.identityKey(item.baseRule()) + "\u0000"
                            + AnswerStructuredAidPolicy.identityKey(item.competingRule());
                    if (!pairs.add(identity)) throw new IllegalArgumentException("duplicate rule priority pair");
                })
                .toList();
    }

    boolean requiresRulePriority(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.RULE_PRIORITY);
    }

    private RulePriorityResolution resolveOne(ModelRequest modelRequest, ModelDraft draft, RulePriorityRequest item) {
        if (item == null) throw new IllegalArgumentException("rule priority item is null");
        return new RulePriorityResolution(
                AnswerStructuredAidPolicy.requiredText(item.baseRule(), "base rule"),
                AnswerStructuredAidPolicy.requiredText(item.competingRule(), "competing rule"),
                AnswerStructuredAidPolicy.requiredText(item.resolution(), "priority resolution"),
                AnswerStructuredAidPolicy.enumValue(item.basis(), RulePriorityBasis.class, "rule priority basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "rule priority resolution"));
    }
}
