package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTieRequest;
import com.rulepilot.assistant.domain.RuleTieResolution;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected tie-resolution aid. */
final class AnswerTieResolver {

    List<RuleTieResolution> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("tie input is invalid");
        List<RuleTieRequest> proposed = draft.tieResolutions();
        AnswerStructuredAidPolicy.validateSelection(request, AnswerAid.TIE, proposed.isEmpty(), "tie resolutions");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    if (!contexts.add(AnswerStructuredAidPolicy.identityKey(item.tieContext()))) {
                        throw new IllegalArgumentException("duplicate tie context");
                    }
                })
                .toList();
    }

    boolean requiresTie(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.TIE);
    }

    private RuleTieResolution resolveOne(ModelRequest modelRequest, ModelDraft draft, RuleTieRequest item) {
        if (item == null || item.resolutionSteps() == null || item.resolutionSteps().isEmpty()) {
            throw new IllegalArgumentException("tie steps are invalid");
        }
        List<String> steps = item.resolutionSteps().stream()
                .map(step -> AnswerStructuredAidPolicy.requiredText(step, "tie step"))
                .toList();
        TieResolutionBasis basis = AnswerStructuredAidPolicy.enumValue(
                item.basis(), TieResolutionBasis.class, "tie basis");
        if (basis == TieResolutionBasis.ORDERED_TIEBREAKERS && steps.size() < 2) {
            throw new IllegalArgumentException("ordered tie-breakers require at least two steps");
        }
        return new RuleTieResolution(
                AnswerStructuredAidPolicy.requiredText(item.tieContext(), "tie context"),
                steps,
                AnswerStructuredAidPolicy.requiredText(item.finalOutcome(), "tie final outcome"),
                basis,
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "tie resolution"));
    }
}
