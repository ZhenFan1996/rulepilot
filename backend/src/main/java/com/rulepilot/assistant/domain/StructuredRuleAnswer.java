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
        AnswerBasis answerBasis,
        boolean official,
        UUID confirmedRulingId,
        Long confirmedRulingVersion,
        String clarification) {

    public StructuredRuleAnswer {
        if (documentVersionId == null || status == null || shortVerdict == null || citations == null
                || exceptions == null || confidence == null) {
            throw new IllegalArgumentException("structured rule answer is invalid");
        }
        citations = List.copyOf(citations);
        exceptions = List.copyOf(exceptions);
        if (status == AnswerStatus.ANSWERED && citations.isEmpty()) {
            throw new IllegalArgumentException("answered rule response requires citations");
        }
        answerBasis = status == AnswerStatus.ANSWERED
                ? answerBasis == null ? AnswerBasis.DIRECT_RULE : answerBasis
                : null;
        if (status != AnswerStatus.ANSWERED && !citations.isEmpty()) {
            throw new IllegalArgumentException("non-answered rule response cannot contain citations");
        }
        if ((confirmedRulingId == null) != (confirmedRulingVersion == null)
                || confirmedRulingVersion != null && confirmedRulingVersion < 0
                || status != AnswerStatus.ANSWERED && confirmedRulingId != null) {
            throw new IllegalArgumentException("confirmed ruling answer identity is invalid");
        }
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification) {
        this(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                null,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification);
    }
}
