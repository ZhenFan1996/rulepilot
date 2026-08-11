package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.RuleConceptComparison;
import java.util.LinkedHashSet;
import java.util.List;

/** Validates the schema and evidence scope of the model-selected concept comparison. */
final class AnswerConceptComparisonResolver {

    private static final int MAX_COMPARISONS = 3;

    List<RuleConceptComparison> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("concept comparison input is invalid");
        }
        List<RuleConceptComparisonRequest> proposed = draft.conceptComparisons();
        AnswerStructuredAidPolicy.validateSelection(
                request, AnswerAid.CONCEPT_COMPARISON, proposed.isEmpty(), "concept comparisons");
        if (proposed.isEmpty()) return List.of();
        if (proposed.size() > MAX_COMPARISONS) {
            throw new IllegalArgumentException("too many concept comparisons");
        }

        LinkedHashSet<String> pairs = new LinkedHashSet<>();
        return proposed.stream()
                .map(item -> resolveOne(request, draft, item))
                .peek(item -> {
                    String identity = AnswerStructuredAidPolicy.identityKey(
                            item.leftConcept() + "\u0000" + item.rightConcept());
                    if (!pairs.add(identity)) throw new IllegalArgumentException("duplicate concept comparison");
                })
                .toList();
    }

    boolean requiresConceptComparison(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.CONCEPT_COMPARISON);
    }

    private RuleConceptComparison resolveOne(
            ModelRequest modelRequest, ModelDraft draft, RuleConceptComparisonRequest item) {
        if (item == null) throw new IllegalArgumentException("concept comparison item is null");
        String leftConcept = AnswerStructuredAidPolicy.requiredText(item.leftConcept(), 120, "left concept");
        String rightConcept = AnswerStructuredAidPolicy.requiredText(item.rightConcept(), 120, "right concept");
        String leftDefinition = AnswerStructuredAidPolicy.requiredText(
                item.leftDefinition(), 600, "left definition");
        String rightDefinition = AnswerStructuredAidPolicy.requiredText(
                item.rightDefinition(), 600, "right definition");
        if (AnswerStructuredAidPolicy.identityKey(leftConcept)
                        .equals(AnswerStructuredAidPolicy.identityKey(rightConcept))
                || AnswerStructuredAidPolicy.identityKey(leftDefinition)
                        .equals(AnswerStructuredAidPolicy.identityKey(rightDefinition))) {
            throw new IllegalArgumentException("concept comparison sides must be distinct");
        }
        return new RuleConceptComparison(
                leftConcept,
                leftDefinition,
                rightConcept,
                rightDefinition,
                AnswerStructuredAidPolicy.requiredText(item.commonGround(), 500, "common ground"),
                AnswerStructuredAidPolicy.requiredText(item.keyDifference(), 700, "key difference"),
                AnswerStructuredAidPolicy.requiredText(item.practicalBoundary(), 600, "practical boundary"),
                AnswerStructuredAidPolicy.enumValue(
                        item.basis(), ConceptComparisonBasis.class, "concept comparison basis"),
                AnswerStructuredAidPolicy.citations(
                        modelRequest, draft, item.citationIds(), "concept comparison"));
    }
}
