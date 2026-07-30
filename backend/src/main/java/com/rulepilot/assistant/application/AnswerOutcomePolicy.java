package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure mappings from verified workflow outcomes to player-visible answer shapes. */
final class AnswerOutcomePolicy {

    private AnswerOutcomePolicy() {}

    static RuleAnswering.AnswerResult publicReaderAnswer(UUID assistantRunId, StructuredRuleAnswer answer) {
        return new RuleAnswering.AnswerResult(
                assistantRunId,
                new RuleAnswering.Answer(
                        answer.status().name(),
                        answer.shortVerdict(),
                        answer.explanation(),
                        answer.citations().stream()
                                .map(citation -> new RuleAnswering.Citation(
                                        citation.heading(), citation.pageFrom(), citation.pageTo()))
                                .toList(),
                        answer.exceptions(),
                        answer.confidence().name(),
                        answer.answerBasis() == null ? null : answer.answerBasis().name(),
                        answer.clarification(),
                        answer.warnings().stream()
                                .map(warning -> new RuleAnswering.Warning(warning.type().name()))
                                .toList()),
                answer.citations().stream()
                        .map(RuleCitation::chunkId)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    static StructuredRuleAnswer confirmedRuling(ConfirmedRulingLookup.ConfirmedAnswer ruling) {
        List<RuleCitation> citations = ruling.citations().stream().map(citation -> new RuleCitation(
                citation.chunkId(),
                citation.documentVersionId(),
                citation.sectionType(),
                citation.heading(),
                citation.excerpt(),
                citation.pageFrom(),
                citation.pageTo())).toList();
        return new StructuredRuleAnswer(
                ruling.documentVersionId(),
                AnswerStatus.ANSWERED,
                ruling.shortVerdict(),
                ruling.explanation(),
                citations,
                ruling.exceptions(),
                AnswerConfidence.valueOf(ruling.confidence()),
                ruling.official(),
                ruling.rulingId(),
                ruling.version(),
                null);
    }

    static StructuredRuleAnswer clarification(UnderstoodQuestion question) {
        String missing = question.missingContext().stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(", "));
        return new StructuredRuleAnswer(
                question.documentVersionId(),
                AnswerStatus.CLARIFICATION_REQUIRED,
                "需要补充上下文后才能查证规则。",
                "缺少信息：" + missing,
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                "请补充 " + missing + "。");
    }

    static StructuredRuleAnswer safeFailure(UUID documentVersionId, AnswerStatus status, String message) {
        return new StructuredRuleAnswer(
                documentVersionId,
                status,
                message,
                message,
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                null);
    }

    static StructuredRuleAnswer insufficientWithSources(
            UUID documentVersionId, String message, List<HybridEvidenceHit> evidence) {
        List<RuleCitation> sources = evidence.stream()
                .map(HybridEvidenceHit::evidence)
                .map(source -> new RuleCitation(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo()))
                .distinct()
                .limit(3)
                .toList();
        return new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.INSUFFICIENT_EVIDENCE,
                message,
                message,
                sources,
                List.of(),
                AnswerConfidence.LOW,
                null,
                false,
                null,
                null,
                null,
                List.of());
    }

    static StructuredRuleAnswer withWarnings(StructuredRuleAnswer answer, List<AnswerWarning> warnings) {
        if (!answer.status().publishesConclusion() || warnings == null || warnings.isEmpty()) {
            return answer;
        }
        LinkedHashSet<AnswerWarning> merged = new LinkedHashSet<>(answer.warnings());
        merged.addAll(warnings);
        return new StructuredRuleAnswer(
                answer.documentVersionId(),
                AnswerStatus.ANSWERED_WITH_WARNING,
                answer.shortVerdict(),
                answer.explanation(),
                answer.citations(),
                answer.exceptions(),
                answer.confidence(),
                answer.answerBasis(),
                answer.official(),
                answer.confirmedRulingId(),
                answer.confirmedRulingVersion(),
                answer.clarification(),
                List.copyOf(merged));
    }
}
