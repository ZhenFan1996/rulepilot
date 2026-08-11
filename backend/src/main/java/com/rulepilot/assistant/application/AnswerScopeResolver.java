package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected applicability aid. */
final class AnswerScopeResolver {

    private static final int MAX_RESOLUTIONS = 3;

    List<RuleScopeResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("scope input is invalid");
        List<RuleScopeRequest> proposed = draft.scopeResolutions();
        AnswerStructuredAidPolicy.validateSelection(request, AnswerAid.SCOPE, proposed.isEmpty(), "scope resolutions");
        if (proposed.isEmpty()) return List.of();
        if (proposed.size() > MAX_RESOLUTIONS) throw new IllegalArgumentException("too many scope resolutions");

        LinkedHashSet<String> situations = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    String identity = AnswerStructuredAidPolicy.identityKey(
                            item.ruleContext() + "\u0000" + item.currentSituation());
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
                AnswerStructuredAidPolicy.requiredText(item.ruleContext(), 500, "scope rule context"),
                AnswerStructuredAidPolicy.requiredText(item.governingCondition(), 500, "scope condition"),
                AnswerStructuredAidPolicy.requiredText(item.currentSituation(), 300, "current situation"),
                AnswerStructuredAidPolicy.enumValue(
                        item.matchStatus(), ScopeMatchStatus.class, "scope match status"),
                AnswerStructuredAidPolicy.requiredText(item.effect(), 600, "scope effect"),
                AnswerStructuredAidPolicy.enumValue(item.basis(), ScopeBasis.class, "scope basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "scope resolution"));
    }
}
