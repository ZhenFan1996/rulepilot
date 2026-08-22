package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTimingRequest;
import com.rulepilot.assistant.domain.RuleTimingResolution;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected timing aid. */
final class AnswerTimingResolver {

    List<RuleTimingResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("timing input is invalid");
        List<RuleTimingRequest> proposed = draft.timingResolutions();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.TIMING, proposed.isEmpty(), "timing resolutions");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    if (!contexts.add(AnswerStructuredAidPolicy.identityKey(item.timingContext()))) {
                        throw new IllegalArgumentException("duplicate timing context");
                    }
                })
                .toList();
    }

    boolean requiresTiming(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.TIMING);
    }

    private RuleTimingResolution resolveOne(ModelRequest modelRequest, ModelDraft draft, RuleTimingRequest item) {
        if (item == null) throw new IllegalArgumentException("timing item is null");
        return new RuleTimingResolution(
                AnswerStructuredAidPolicy.requiredText(item.timingContext(), "timing context"),
                AnswerStructuredAidPolicy.requiredText(item.resolutionOrder(), "resolution order"),
                AnswerStructuredAidPolicy.requiredText(item.orderSource(), "order source"),
                item.basis(),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "timing resolution"));
    }
}
