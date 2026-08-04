package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.RuleTermDefinition;
import java.util.List;

/** Keeps prose bounded when validated structured definitions already carry the complete rule content. */
final class AnswerDefinitionProsePolicy {

    private static final int VERDICT_LIMIT = 240;
    private static final int EXPLANATION_LIMIT = 1500;

    private AnswerDefinitionProsePolicy() {}

    static Result normalize(String shortVerdict, String explanation, List<RuleTermDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) return new Result(shortVerdict, explanation);
        boolean chinese = containsCjk(shortVerdict) || containsCjk(explanation);
        String normalizedVerdict = shortVerdict != null && shortVerdict.length() > VERDICT_LIMIT
                ? chinese ? "所问规则术语已在下方逐项定义，并分别附有引用。"
                        : "The requested rule terms are defined separately below with their citations."
                : shortVerdict;
        String normalizedExplanation = explanation != null && explanation.length() > EXPLANATION_LIMIT
                ? chinese ? "请查看下方逐项定义；每条边界只保留规则证据明确支持的范围或区别。"
                        : "See the definitions below; each boundary includes only a scope or distinction supported by the cited rules."
                : explanation;
        return new Result(normalizedVerdict, normalizedExplanation);
    }

    private static boolean containsCjk(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA;
        });
    }

    record Result(String shortVerdict, String explanation) {}
}
