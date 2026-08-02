package com.rulepilot.assistant.application;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Converts stored visual audit observations into bounded mechanical evidence for the answer model. */
final class AnswerVisualFactPresentationPolicy {

    private static final Pattern RESOLVED_DEPICTED_REWARD = Pattern.compile(
            "(?iu)(\\b(?:gain|gives?|grants?|receive|receives|获得|得到|给予)\\s+)"
                    + "(?:the\\s+)?(?:depicted|shown|pictured|图示|所示)\\s+(?:reward|奖励)\\s*[:：]\\s*");
    private static final Pattern APPEARANCE_SUFFIX = Pattern.compile(
            "(?iu)(?:[,.;，。；]\\s*)"
                    + "(?:(?:the|this)\\s+)?(?:card(?:'s)?\\s+)?(?:also\\s+)?"
                    + "(?:(?:lower[- ]space\\s+reward|upper[- ]space|下格奖励|上格)\\s+)?"
                    + "(?:depicts?|shows?|shown\\s+as|as\\s+shown|pictured|图示|显示|画着|描绘)\\b");
    private static final Pattern APPEARANCE_ONLY_IDENTIFIER_LINE = Pattern.compile(
            "(?iu)^\\s*[\\p{L}]{1,4}\\s*[#_-]?\\s*\\d{1,4}\\s+"
                    + "(?:depicts?|shows?|pictured|图示|显示|画着|描绘)\\b");

    private AnswerVisualFactPresentationPolicy() {}

    static String evidenceText(PageFactMatch fact) {
        String mechanicalSummary = mechanicalSummary(fact.factualSummary());
        return "Visual page facts (verify against the cited rulebook page).\nVisible facts: " + mechanicalSummary;
    }

    static String mechanicalSummary(String factualSummary) {
        if (factualSummary == null || factualSummary.isBlank()) return "";
        return factualSummary.lines()
                .map(AnswerVisualFactPresentationPolicy::mechanicalLine)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    private static String mechanicalLine(String source) {
        String normalized = RESOLVED_DEPICTED_REWARD.matcher(source.strip()).replaceAll("$1");
        var suffix = APPEARANCE_SUFFIX.matcher(normalized);
        if (suffix.find()) normalized = normalized.substring(0, suffix.start()).strip();
        if (APPEARANCE_ONLY_IDENTIFIER_LINE.matcher(normalized).find()) return "";
        return normalized.replaceAll("[;；,，]$", "").strip();
    }
}
