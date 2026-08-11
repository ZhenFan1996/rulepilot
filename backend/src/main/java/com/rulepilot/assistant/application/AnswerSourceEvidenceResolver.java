package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.UUID;

/** Validates the bounded citation scope of a source-focused answer. */
final class AnswerSourceEvidenceResolver {

    List<UUID> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("source evidence input is invalid");
        if (!requiresSourceEvidence(request) || !draft.answerable()) return List.of();
        if (!"DIRECT_RULE".equalsIgnoreCase(draft.answerBasis())) {
            throw new IllegalArgumentException("source-focused answer must use direct rule evidence");
        }
        List<UUID> citations = AnswerStructuredAidPolicy.citations(
                request, draft, draft.citationIds(), "source-focused answer");
        if (citations.size() > 2) {
            throw new IllegalArgumentException("source-focused answer requires one or two direct citations");
        }
        AnswerStructuredAidPolicy.requiredText(draft.shortVerdict(), 800, "source-focused verdict");
        AnswerStructuredAidPolicy.requiredText(draft.explanation(), 4_000, "source-focused explanation");
        return citations;
    }

    static boolean requiresSourceEvidence(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.SOURCE);
    }
}
