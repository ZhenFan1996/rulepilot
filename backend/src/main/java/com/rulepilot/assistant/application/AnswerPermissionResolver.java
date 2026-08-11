package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.UUID;

/** Validates the bounded citation scope of a permission-focused answer. */
final class AnswerPermissionResolver {

    List<UUID> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) throw new IllegalArgumentException("permission input is invalid");
        if (!requiresPermission(request) || !draft.answerable()) return List.of();
        List<UUID> citations = AnswerStructuredAidPolicy.citations(
                request, draft, draft.citationIds(), "permission ruling");
        AnswerStructuredAidPolicy.requiredText(draft.shortVerdict(), 800, "permission verdict");
        AnswerStructuredAidPolicy.requiredText(draft.explanation(), 4_000, "permission explanation");
        return citations;
    }

    static boolean requiresPermission(ModelRequest request) {
        return AnswerStructuredAidPolicy.required(request, AnswerAid.PERMISSION);
    }
}
