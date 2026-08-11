package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WorkedExampleRequest;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of model-selected worked examples. */
final class AnswerWorkedExampleResolver {

    private static final int MAX_EXAMPLES = 3;

    List<RuleWorkedExample> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("worked example input is invalid");
        List<WorkedExampleRequest> proposed = draft.workedExamples();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.EXAMPLE, proposed.isEmpty(), "worked examples");
        if (proposed.isEmpty()) return List.of();
        if (proposed.size() > MAX_EXAMPLES) throw new IllegalArgumentException("too many worked examples");

        LinkedHashSet<String> examples = new LinkedHashSet<>();
        return proposed.stream()
                .map(example -> resolveOne(request, draft, example))
                .peek(example -> {
                    String identity = AnswerStructuredAidPolicy.identityKey(
                            example.setup() + "\u0000" + example.action() + "\u0000" + example.outcome());
                    if (!examples.add(identity)) throw new IllegalArgumentException("duplicate worked example");
                })
                .toList();
    }

    boolean requiresWorkedExamples(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.EXAMPLE);
    }

    private RuleWorkedExample resolveOne(ModelRequest modelRequest, ModelDraft draft, WorkedExampleRequest item) {
        if (item == null) throw new IllegalArgumentException("worked example is null");
        return new RuleWorkedExample(
                AnswerStructuredAidPolicy.requiredText(item.setup(), 500, "worked example setup"),
                AnswerStructuredAidPolicy.requiredText(item.action(), 700, "worked example action"),
                AnswerStructuredAidPolicy.requiredText(item.outcome(), 500, "worked example outcome"),
                AnswerStructuredAidPolicy.enumValue(item.basis(), WorkedExampleBasis.class, "worked example basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "worked example"));
    }
}
