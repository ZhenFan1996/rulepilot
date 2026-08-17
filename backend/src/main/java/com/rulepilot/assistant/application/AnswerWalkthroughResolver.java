package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the walkthrough selected by the answer plan. */
final class AnswerWalkthroughResolver {

    List<RuleWalkthroughStep> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("walkthrough input is invalid");
        }
        List<WalkthroughStepRequest> proposed = draft.walkthroughSteps();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.WALKTHROUGH, proposed.isEmpty(), "walkthrough");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> instructions = new LinkedHashSet<>();
        return proposed.stream()
                .map(step -> resolveOne(request, draft, step))
                .peek(step -> {
                    if (!instructions.add(AnswerStructuredAidPolicy.identityKey(step.instruction()))) {
                        throw new IllegalArgumentException("duplicate walkthrough instruction");
                    }
                })
                .toList();
    }

    boolean requiresWalkthrough(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.WALKTHROUGH);
    }

    private RuleWalkthroughStep resolveOne(ModelRequest modelRequest, ModelDraft draft, WalkthroughStepRequest item) {
        if (item == null) throw new IllegalArgumentException("walkthrough step is null");
        return new RuleWalkthroughStep(
                AnswerStructuredAidPolicy.requiredText(item.instruction(), "walkthrough instruction"),
                AnswerStructuredAidPolicy.requiredText(item.explanation(), "walkthrough explanation"),
                AnswerStructuredAidPolicy.enumValue(
                        item.orderBasis(), WalkthroughOrderBasis.class, "walkthrough order basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "walkthrough step"));
    }
}
