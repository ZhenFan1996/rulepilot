package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected applicability aid. */
final class AnswerScopeResolver {

    List<RuleScopeResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("scope input is invalid");
        List<RuleScopeRequest> proposed = draft.scopeResolutions();
        AnswerStructuredAidPolicy.validateSelection(request, AnswerAid.SCOPE, proposed.isEmpty(), "scope resolutions");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> situations = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    String identity = AnswerStructuredAidPolicy.identityKey(item.ruleContext()) + "\u0000"
                            + AnswerStructuredAidPolicy.identityKey(item.currentSituation());
                    if (!situations.add(identity)) throw new IllegalArgumentException("duplicate scope resolution");
                })
                .toList();
    }

    boolean requiresScope(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.SCOPE);
    }

    private RuleScopeResolution resolveOne(ModelRequest modelRequest, ModelDraft draft, RuleScopeRequest item) {
        if (item == null) throw new IllegalArgumentException("scope item is null");
        return new RuleScopeResolution(
                AnswerStructuredAidPolicy.requiredText(item.ruleContext(), "scope rule context"),
                AnswerStructuredAidPolicy.requiredText(item.governingCondition(), "scope condition"),
                AnswerStructuredAidPolicy.requiredText(item.currentSituation(), "current situation"),
                item.matchStatus(),
                AnswerStructuredAidPolicy.requiredText(item.effect(), "scope effect"),
                item.basis(),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "scope resolution"));
    }
}
