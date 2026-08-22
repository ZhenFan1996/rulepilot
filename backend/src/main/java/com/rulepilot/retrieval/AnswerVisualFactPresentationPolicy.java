package com.rulepilot.retrieval;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;

/** Presents stored visual observations without rewriting their natural-language meaning. */
final class AnswerVisualFactPresentationPolicy {

    private AnswerVisualFactPresentationPolicy() {}

    static String mechanicalSummary(String factualSummary) {
        if (factualSummary == null || factualSummary.isBlank()) return "";
        return factualSummary.strip();
    }
}
