package com.rulepilot.assistant.application;

import com.rulepilot.assistant.PlayerLocale;
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
        return publicReaderAnswer(assistantRunId, answer, "", PlayerLocale.ZH_CN);
    }

    static RuleAnswering.AnswerResult publicReaderAnswer(
            UUID assistantRunId,
            StructuredRuleAnswer answer,
            String currentQuestion,
            PlayerLocale requestedLanguage) {
        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                answer, currentQuestion, requestedLanguage);
        return new RuleAnswering.AnswerResult(
                assistantRunId,
                new RuleAnswering.Answer(
                        presented.status().name(),
                        presented.shortVerdict(),
                        presented.explanation(),
                        presented.citations().stream()
                                .map(citation -> new RuleAnswering.Citation(
                                        citation.heading(), citation.pageFrom(), citation.pageTo()))
                                .toList(),
                        presented.exceptions(),
                        presented.confidence().name(),
                        presented.answerBasis() == null ? null : presented.answerBasis().name(),
                        presented.clarification(),
                        presented.warnings().stream()
                                .map(warning -> new RuleAnswering.Warning(warning.type().name()))
                                .toList(),
                        presented.calculations(),
                        presented.situationChecks(),
                        presented.walkthroughSteps(),
                        presented.decisionBranches(),
                        presented.exceptionClauses(),
                        presented.termDefinitions(),
                        presented.workedExamples(),
                        presented.priorityResolutions(),
                        presented.timingResolutions(),
                        presented.tieResolutions(),
                        presented.scopeResolutions(),
                        presented.conceptComparisons(),
                        presented.ruleOptions()),
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

    static StructuredRuleAnswer clarification(UnderstoodQuestion question, PlayerLocale locale) {
        PlayerLocale outputLanguage = locale == null ? PlayerLocale.ZH_CN : locale;
        List<String> requests = question.missingContext().stream()
                .sorted()
                .map(missing -> clarificationRequest(missing, outputLanguage))
                .toList();
        String detail = String.join(outputLanguage == PlayerLocale.EN ? " " : "；", requests);
        return new StructuredRuleAnswer(
                question.documentVersionId(),
                AnswerStatus.CLARIFICATION_REQUIRED,
                outputLanguage == PlayerLocale.EN
                        ? "I need one more detail before I can verify the rule."
                        : "需要补充一项信息后才能查证规则。",
                outputLanguage == PlayerLocale.EN
                        ? "The question contains a reference that cannot be resolved safely."
                        : "问题中有无法安全确定的指代。",
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                detail);
    }

    private static String clarificationRequest(
            com.rulepilot.assistant.domain.MissingQuestionContext missing,
            PlayerLocale locale) {
        if (locale == PlayerLocale.EN) {
            return switch (missing) {
                case REFERENCED_OBJECT ->
                    "What exactly does “this”, “that”, or “it” refer to? Include the rulebook name of the card, action, effect, or area.";
                case SITUATION_DETAILS ->
                    "Include the object being resolved, when it happens, and what occurred immediately before it.";
            };
        }
        return switch (missing) {
            case REFERENCED_OBJECT ->
                "你说的“这个”“那个”或“它”具体指什么？请写出规则书里的卡牌、行动、效果或区域名称。";
            case SITUATION_DETAILS ->
                "请补充要判断的对象、发生时机，以及紧接着之前发生了什么。";
        };
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
                        AnswerCitationPresentationPolicy.excerpt(source.excerpt()),
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
                List.copyOf(merged),
                answer.calculations(),
                answer.situationChecks(),
                answer.walkthroughSteps(),
                answer.decisionBranches(),
                answer.exceptionClauses(),
                answer.termDefinitions(),
                answer.workedExamples(),
                answer.priorityResolutions(),
                answer.timingResolutions(),
                answer.tieResolutions(),
                answer.scopeResolutions(),
                answer.conceptComparisons(),
                answer.ruleOptions());
    }
}
