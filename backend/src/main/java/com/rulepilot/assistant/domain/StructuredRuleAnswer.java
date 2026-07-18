package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record StructuredRuleAnswer(
        UUID documentVersionId,
        AnswerStatus status,
        String shortVerdict,
        String explanation,
        List<RuleCitation> citations,
        List<String> exceptions,
        AnswerConfidence confidence,
        boolean official,
        String clarification) {

    public StructuredRuleAnswer {
        if (documentVersionId == null || status == null || shortVerdict == null || citations == null
                || exceptions == null || confidence == null) {
            throw new IllegalArgumentException("structured rule answer is invalid");
        }
        citations = List.copyOf(citations);
        exceptions = List.copyOf(exceptions);
    }
}
