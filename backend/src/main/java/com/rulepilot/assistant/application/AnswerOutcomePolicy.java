package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.ruling.ConfirmedRulingLookup;
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
                        answer.clarification()),
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
}
