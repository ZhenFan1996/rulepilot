package com.rulepilot.retrieval;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import java.util.stream.Collectors;

/** Presents stored visual observations without rewriting their natural-language meaning. */
final class AnswerVisualFactPresentationPolicy {

    private AnswerVisualFactPresentationPolicy() {}

    static String evidenceText(PageFactMatch fact) {
        return "Visual page facts (literal observations only; verify rules against the cited page).\nVisible facts: "
                + mechanicalSummary(fact.factualSummary());
    }

    static String transcribedRuleEvidenceText(PageFactMatch fact) {
        return VisualTranscribedRuleEvidence.render(mechanicalSummary(fact.factualSummary()));
    }

    static String mechanicalSummary(String factualSummary) {
        if (factualSummary == null || factualSummary.isBlank()) return "";
        return factualSummary.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining("\n"));
    }
}
