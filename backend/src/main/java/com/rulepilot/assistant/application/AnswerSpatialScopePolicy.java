package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.PlayerLocale;
import java.util.Locale;
import java.util.regex.Pattern;

/** Detects a common visual-rule overreach before it becomes a table-side restriction. */
final class AnswerSpatialScopePolicy {

    private static final Pattern POSITIONAL_QUESTION = Pattern.compile(
            "(?iu)(?:左|右|上|下|行|列|位置|标记|相邻)|"
                    + "\\b(?:left|right|top|bottom|row|column|marker|adjacent)\\b");
    private static final Pattern EXTRA_COORDINATE = Pattern.compile(
            "(?iu)(?:最上|最下|中间|最右|第[二三四][行列])|"
                    + "\\b(?:top(?:\s+row)?|bottom(?:\s+row)?|middle(?:\s+row)?|right(?:\s+column)?|"
                    + "second\s+(?:row|column)|third\s+(?:row|column))\\b");

    private AnswerSpatialScopePolicy() {}

    static boolean needsRepair(ModelRequest request, ModelDraft draft) {
        if (draft == null || !draft.answerable() || !POSITIONAL_QUESTION.matcher(request.question()).find()) {
            return false;
        }
        String answer = (safe(draft.shortVerdict()) + "\n" + safe(draft.explanation())).toLowerCase(Locale.ROOT);
        var matcher = EXTRA_COORDINATE.matcher(answer);
        if (!matcher.find()) return false;
        String question = request.question().toLowerCase(Locale.ROOT);
        do {
            if (!question.contains(matcher.group().toLowerCase(Locale.ROOT))) {
                return true;
            }
        } while (matcher.find());
        return false;
    }

    /** Keeps a useful, cited partial ruling when one bounded repair still invents board geometry. */
    static ModelDraft boundRepeatedInference(ModelRequest request, ModelDraft draft) {
        if (!needsRepair(request, draft)) return draft;
        boolean english = request.context().outputLanguage() == PlayerLocale.EN;
        return new ModelDraft(
                true,
                null,
                english
                        ? "Your stated marker position establishes one restriction, but not a reliable list of every other space."
                        : "你说明的标记位置能确定一项限制，但不足以可靠列出其他每个位置。",
                english
                        ? "Apply the cited rule to the object the marker directly restricts. Do not extend “beside” or "
                                + "“next to” into extra rows or columns unless the rulebook explicitly does so. "
                                + "Check the marker's exact printed placement on the cited page before ruling out any "
                                + "additional position."
                        : "先把引用规则套用到标记直接限制的对象上。除非规则书明确写出，否则“在旁边”不能外推为其他行或列也受限。"
                                + "请对照来源页中标记的实际落点，再排除任何额外位置。",
                draft.citationIds(),
                draft.exceptions(),
                "MEDIUM",
                "GROUNDED_APPLICATION",
                draft.calculations(),
                draft.situationChecks(),
                draft.walkthroughSteps(),
                draft.decisionBranches(),
                draft.exceptionClauses(),
                draft.termDefinitions(), draft.workedExamples(), draft.priorityResolutions(), draft.timingResolutions(),
                draft.tieResolutions(), draft.scopeResolutions(), draft.conceptComparisons(), draft.ruleOptions());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
