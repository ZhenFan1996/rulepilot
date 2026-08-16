package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.Locale;

/** Detects source-wide absence claims that cannot be justified by a bounded retrieval result. */
final class AnswerSourceScopeRepairPolicy {

    private static final List<String> WHOLE_SOURCE_NEGATIVES = List.of(
            "规则书没有",
            "规则书中没有",
            "规则书里没有",
            "规则书未提供",
            "规则书未提及",
            "规则书未提到",
            "规则书未说明",
            "规则书不包含",
            "规则书只描述",
            "规则书只说明",
            "规则书仅描述",
            "规则书仅说明",
            "the rulebook does not",
            "the rulebook doesn't",
            "the rulebook has no",
            "the rulebook provides no",
            "the rulebook contains no",
            "not in the rulebook");

    private AnswerSourceScopeRepairPolicy() {}

    static List<String> feedbackFor(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null || !draft.answerable()) return List.of();
        String allProse = allProse(draft);
        if (!containsWholeSourceNegative(allProse)) return List.of();
        return List.of(
                "SOURCE_SCOPE HARD FAILURE: A bounded search result cannot establish what the entire rulebook omits. "
                        + "In every prose field, replace only claims that the rulebook lacks, omits, or does not provide "
                        + "something with the honest local boundary that the current supplied excerpts cannot confirm it. "
                        + "Preserve all other supported prose, details, citationIds, and structured items. Do not paraphrase "
                        + "the same whole-source negative and do not invent the missing advice.");
    }

    static boolean requiresRepair(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null || !draft.answerable()) return false;
        return containsWholeSourceNegative(allProse(draft));
    }

    static boolean requiresRepair(String prose) {
        return prose != null && containsWholeSourceNegative(prose.toLowerCase(Locale.ROOT));
    }

    private static String allProse(ModelDraft draft) {
        return String.join("\n", draft.shortVerdict(), draft.explanation(), String.join("\n", draft.exceptions()))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsWholeSourceNegative(String prose) {
        return WHOLE_SOURCE_NEGATIVES.stream().anyMatch(prose::contains);
    }

}
