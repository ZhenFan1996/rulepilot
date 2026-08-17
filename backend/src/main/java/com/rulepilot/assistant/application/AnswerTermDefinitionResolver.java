package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.TermDefinitionRequest;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of model-selected rulebook definitions. */
final class AnswerTermDefinitionResolver {

    List<RuleTermDefinition> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("term definition input is invalid");
        List<TermDefinitionRequest> proposed = draft.termDefinitions();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.DEFINITIONS, proposed.isEmpty(), "term definitions");
        if (proposed.isEmpty()) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        return proposed.stream()
                .map(definition -> resolveOne(request, draft, definition))
                .peek(definition -> {
                    if (!terms.add(AnswerStructuredAidPolicy.identityKey(definition.term()))) {
                        throw new IllegalArgumentException("duplicate term definition");
                    }
                })
                .toList();
    }

    boolean requiresTermDefinitions(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.DEFINITIONS);
    }

    private RuleTermDefinition resolveOne(ModelRequest modelRequest, ModelDraft draft, TermDefinitionRequest item) {
        if (item == null) throw new IllegalArgumentException("term definition is null");
        return new RuleTermDefinition(
                AnswerStructuredAidPolicy.requiredText(item.term(), "term"),
                AnswerStructuredAidPolicy.requiredText(item.definition(), "definition"),
                AnswerStructuredAidPolicy.optionalText(item.boundary()),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "term definition"));
    }
}
