package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerBasis;
import java.util.List;
import java.util.UUID;

/** Validates the bounded citation scope of a source-focused answer. */
final class AnswerSourceEvidenceResolver {

    List<UUID> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("source evidence input is invalid");
        if (!requiresSourceEvidence(request) || !draft.answerable()) return List.of();
        if (draft.answerBasis() != AnswerBasis.DIRECT_RULE) {
            throw new IllegalArgumentException("source-focused answer must use direct rule evidence");
        }
        List<UUID> citations = AnswerStructuredAidPolicy.citations(
                request, draft, draft.citationIds(), "source-focused answer");
        return citations;
    }

    static boolean requiresSourceEvidence(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.SOURCE);
    }
}
