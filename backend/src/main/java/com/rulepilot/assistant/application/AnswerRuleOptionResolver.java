package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleOptionRequest;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected option list. */
final class AnswerRuleOptionResolver {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 8;

    List<RuleOption> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("rule option input is invalid");
        List<RuleOptionRequest> proposed = draft.ruleOptions();
        AnswerStructuredAidPolicy.validateSelection(request, AnswerAid.OPTIONS, proposed.isEmpty(), "rule options");
        if (proposed.isEmpty()) return List.of();
        if (proposed.size() < MIN_OPTIONS || proposed.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("rule option count is invalid");
        }

        List<RuleOption> resolved = proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .toList();
        validateCoherentSet(resolved);
        return resolved;
    }

    boolean requiresRuleOptions(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.OPTIONS);
    }

    private RuleOption resolveOne(ModelRequest modelRequest, ModelDraft draft, RuleOptionRequest item) {
        if (item == null) throw new IllegalArgumentException("rule option item is null");
        return new RuleOption(
                AnswerStructuredAidPolicy.requiredText(item.decisionContext(), 240, "option decision context"),
                AnswerStructuredAidPolicy.requiredText(item.selectionRule(), 400, "option selection rule"),
                AnswerStructuredAidPolicy.requiredText(item.optionName(), 160, "option name"),
                AnswerStructuredAidPolicy.requiredText(item.availabilityCondition(), 500, "option availability"),
                AnswerStructuredAidPolicy.requiredText(item.result(), 700, "option result"),
                AnswerStructuredAidPolicy.enumValue(item.basis(), RuleOptionBasis.class, "rule option basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "rule option"));
    }

    private void validateCoherentSet(List<RuleOption> options) {
        RuleOption first = options.getFirst();
        String context = AnswerStructuredAidPolicy.identityKey(first.decisionContext());
        String selection = AnswerStructuredAidPolicy.identityKey(first.selectionRule());
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (RuleOption option : options) {
            if (!context.equals(AnswerStructuredAidPolicy.identityKey(option.decisionContext()))
                    || !selection.equals(AnswerStructuredAidPolicy.identityKey(option.selectionRule()))
                    || option.basis() != first.basis()) {
                throw new IllegalArgumentException("rule options do not describe one coherent choice set");
            }
            if (!names.add(AnswerStructuredAidPolicy.identityKey(option.optionName()))) {
                throw new IllegalArgumentException("rule option names must be unique");
            }
        }
    }
}
