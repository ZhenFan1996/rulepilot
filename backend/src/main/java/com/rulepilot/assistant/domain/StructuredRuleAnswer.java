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
        String clarification,
        List<AnswerWarning> warnings) {

    public StructuredRuleAnswer {
        if (documentVersionId == null || status == null || shortVerdict == null || citations == null
                || exceptions == null || confidence == null) {
            throw new IllegalArgumentException("structured rule answer is invalid");
        }
        citations = List.copyOf(citations);
        exceptions = List.copyOf(exceptions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (status.publishesConclusion() && citations.isEmpty()) {
            throw new IllegalArgumentException("answered rule response requires citations");
        }
        answerBasis = status.publishesConclusion()
                ? answerBasis == null ? AnswerBasis.DIRECT_RULE : answerBasis
                : null;
        if (!status.publishesConclusion() && status != AnswerStatus.INSUFFICIENT_EVIDENCE && !citations.isEmpty()) {
            throw new IllegalArgumentException("only an evidence-insufficient response may expose sources");
        }
        if ((status == AnswerStatus.ANSWERED_WITH_WARNING) != !warnings.isEmpty()) {
            throw new IllegalArgumentException("answer warning status and warnings must agree");
        }
        if ((confirmedRulingId == null) != (confirmedRulingVersion == null)
                || confirmedRulingVersion != null && confirmedRulingVersion < 0
                || !status.publishesConclusion() && confirmedRulingId != null) {
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
            AnswerBasis answerBasis,
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
                answerBasis,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                List.of());
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
                clarification,
                List.of());
    }
}
