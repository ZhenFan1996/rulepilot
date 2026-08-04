package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.RuleExceptionClause;
import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

/** Keeps a cited summary readable without deleting the complete validated explanation or structured details. */
final class AnswerShortVerdictPolicy {

    private static final int PUBLICATION_LIMIT = 240;
    private static final int NORMALIZED_LIMIT = 200;

    private AnswerShortVerdictPolicy() {}

    static String normalizeCitedSummary(String verdict, List<RuleExceptionClause> exceptionClauses) {
        if (verdict == null || verdict.length() <= PUBLICATION_LIMIT) {
            return verdict;
        }
        boolean chinese = verdict.codePoints().anyMatch(AnswerShortVerdictPolicy::isCjk);
        boolean hasStructuredExceptions = exceptionClauses != null && !exceptionClauses.isEmpty();
        String suffix = hasStructuredExceptions
                ? chinese
                        ? "完整条件和后果见下方逐条引用的例外清单。"
                        : "See the cited exception list below for every condition and effect."
                : "";
        String fallback = hasStructuredExceptions
                ? chinese
                        ? "请按下方逐条引用的例外清单，应用所有符合的条件及其对应后果。"
                        : "Apply every matching condition and effect in the cited exception list below."
                : chinese
                        ? "完整裁定见下方引用支持的说明。"
                        : "See the cited explanation below for the complete ruling.";
        int sentenceLimit = NORMALIZED_LIMIT - suffix.length() - 1;
        String firstSentence = firstCompleteSentence(verdict.strip(), sentenceLimit);
        if (firstSentence.isBlank()) return fallback;
        if (suffix.isBlank()) return firstSentence;
        return firstSentence + (chinese ? "" : " ") + suffix;
    }

    private static String firstCompleteSentence(String verdict, int limit) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(verdict);
        int end = iterator.next();
        if (end == BreakIterator.DONE) return "";
        String sentence = verdict.substring(0, end).strip();
        return sentence.length() <= limit && endsAsSentence(sentence) ? sentence : "";
    }

    private static boolean endsAsSentence(String value) {
        return value.endsWith(".") || value.endsWith("!") || value.endsWith("?")
                || value.endsWith("。") || value.endsWith("！") || value.endsWith("？");
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }
}
