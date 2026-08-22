package com.rulepilot.assistant.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.application.PlayerFacingRuleAnswer.Citation;
import com.rulepilot.assistant.application.PlayerFacingRuleAnswer.Recovery;
import com.rulepilot.assistant.application.PlayerFacingRuleAnswer.SourceKind;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.List;

/** Converts an audited domain answer into the smaller contract a player is allowed to receive. */
public final class PlayerFacingAnswerPresenter {

    private PlayerFacingAnswerPresenter() {}

    public static PlayerFacingRuleAnswer present(
            StructuredRuleAnswer answer, String currentQuestion, PlayerLocale requestedLanguage) {
        if (answer == null) throw new IllegalArgumentException("answer is required");
        String question = currentQuestion == null ? "" : currentQuestion;
        PlayerLocale language = PlayerLocale.forQuestion(question, requestedLanguage);
        List<Citation> citations = answer.citations().stream()
                .map(citation -> new Citation(
                        citation.heading(),
                        citation.excerpt(),
                        citation.pageFrom(),
                        citation.pageTo()))
                .toList();
        if (!answer.status().publishesConclusion()) {
            List<Citation> safeSources = answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE
                    ? citations
                    : List.of();
            return safeFailure(answer.status(), language, question, safeSources, source(answer), answer.clarification());
        }
        return new PlayerFacingRuleAnswer(
                languageTag(language),
                answer.status(),
                answer.shortVerdict(),
                answer.explanation() == null ? "" : answer.explanation(),
                citations,
                answer.exceptions(),
                answer.confidence(),
                answer.answerBasis(),
                source(answer),
                null,
                null,
                answer.warnings(),
                answer.calculations().stream()
                        .map(value -> new RuleAnswering.Calculation(value.expression(), value.result()))
                        .toList(),
                answer.situationChecks().stream()
                        .map(value -> new RuleAnswering.SituationCheck(
                                value.requirement(), value.status().name(), value.playerFact()))
                        .toList(),
                answer.walkthroughSteps().stream()
                        .map(value -> new RuleAnswering.WalkthroughStep(
                                value.instruction(), value.explanation(), value.orderBasis().name()))
                        .toList(),
                answer.decisionBranches().stream()
                        .map(value -> new RuleAnswering.DecisionBranch(
                                value.condition(), value.outcome(), value.basis().name()))
                        .toList(),
                answer.exceptionClauses().stream()
                        .map(value -> new RuleAnswering.ExceptionClause(value.condition(), value.effect()))
                        .toList(),
                answer.termDefinitions().stream()
                        .map(value -> new RuleAnswering.TermDefinition(
                                value.term(), value.definition(), value.boundary()))
                        .toList(),
                answer.workedExamples().stream()
                        .map(value -> new RuleAnswering.WorkedExample(
                                value.setup(), value.action(), value.outcome(), value.basis().name()))
                        .toList(),
                answer.priorityResolutions().stream()
                        .map(value -> new RuleAnswering.RulePriorityResolution(
                                value.baseRule(), value.competingRule(), value.resolution(), value.basis().name()))
                        .toList(),
                answer.timingResolutions().stream()
                        .map(value -> new RuleAnswering.RuleTimingResolution(
                                value.timingContext(), value.resolutionOrder(), value.orderSource(), value.basis().name()))
                        .toList(),
                answer.tieResolutions().stream()
                        .map(value -> new RuleAnswering.RuleTieResolution(
                                value.tieContext(), value.resolutionSteps(), value.finalOutcome(), value.basis().name()))
                        .toList(),
                answer.scopeResolutions().stream()
                        .map(value -> new RuleAnswering.RuleScopeResolution(
                                value.ruleContext(), value.governingCondition(), value.currentSituation(),
                                value.matchStatus().name(), value.effect(), value.basis().name()))
                        .toList(),
                answer.conceptComparisons().stream()
                        .map(value -> new RuleAnswering.RuleConceptComparison(
                                value.leftConcept(), value.leftDefinition(), value.rightConcept(), value.rightDefinition(),
                                value.commonGround(), value.keyDifference(), value.practicalBoundary(), value.basis().name()))
                        .toList(),
                answer.ruleOptions().stream()
                        .map(value -> new RuleAnswering.RuleOption(
                                value.decisionContext(), value.selectionRule(), value.optionName(),
                                value.availabilityCondition(), value.result(), value.basis().name()))
                        .toList());
    }

    private static PlayerFacingRuleAnswer safeFailure(
            AnswerStatus status,
            PlayerLocale language,
            String question,
            List<Citation> citations,
            SourceKind source) {
        return safeFailure(status, language, question, citations, source, null);
    }

    private static PlayerFacingRuleAnswer safeFailure(
            AnswerStatus status,
            PlayerLocale language,
            String question,
            List<Citation> citations,
            SourceKind source,
            String clarification) {
        FailureCopy copy = failureCopy(status, language, question, clarification);
        return new PlayerFacingRuleAnswer(
                languageTag(language),
                status,
                copy.verdict(),
                "",
                citations,
                List.of(),
                AnswerConfidence.LOW,
                null,
                source,
                copy.clarification(),
                copy.recovery(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static FailureCopy failureCopy(
            AnswerStatus status, PlayerLocale language, String question, String clarification) {
        boolean english = language == PlayerLocale.EN;
        if (status == AnswerStatus.CLARIFICATION_REQUIRED) {
            String detail = usableClarification(clarification, language)
                    ? clarification
                    : english
                            ? "Name the exact card, action, effect, or area and when the situation occurs."
                            : "请写明具体的卡牌、行动、效果或区域，以及这个情况发生的时机。";
            return new FailureCopy(
                    english
                            ? "I need one more detail before I can check the rule."
                            : "还需要一项具体信息才能继续查证。",
                    detail,
                    new Recovery(
                            detail,
                            english ? "Add that detail" : "补充这项信息",
                            english ? "I mean: " : "我指的是："));
        }
        if (status == AnswerStatus.INSUFFICIENT_EVIDENCE) {
            String detail = english
                    ? "Add the exact object, timing, or rulebook page, or open the candidate pages below."
                    : "请补充具体对象、发生时机或规则书页码，也可以先查看下方候选页。";
            return new FailureCopy(
                    english
                            ? "The available sources are not enough for a reliable answer yet."
                            : "现有依据还不足以可靠回答这个问题。",
                    null,
                    new Recovery(detail, english ? "Add detail" : "补充细节", ""));
        }
        if (status == AnswerStatus.MODEL_TIMEOUT) {
            String detail = english
                    ? "Your question is still here. Review or edit it, then try again."
                    : "你的问题仍保留在这里；可以先检查或修改，再重新尝试。";
            return new FailureCopy(
                    english
                            ? "I couldn't finish checking the rule in time."
                            : "这次没有在时限内完成规则核对。",
                    null,
                    new Recovery(
                            detail,
                            english ? "Review and try again" : "检查后重试",
                            safeQuestionDraft(question)));
        }
        if (status == AnswerStatus.VERSION_CONFLICT) {
            String detail = english
                    ? "Reload the current rulebook version, review the question, and ask again."
                    : "请先重新载入当前规则书版本，再检查问题并重新提问。";
            return new FailureCopy(
                    english
                            ? "The rulebook changed while I was checking this answer."
                            : "核对期间规则书版本发生了变化。",
                    null,
                    new Recovery(
                            detail,
                            english ? "Review and ask again" : "检查后重新提问",
                            safeQuestionDraft(question)));
        }
        String detail = english
                ? "Your question is still here. Review or edit it, then try again."
                : "你的问题仍保留在这里；可以先检查或修改，再重新尝试。";
        return new FailureCopy(
                english
                        ? "I couldn't verify a reliable answer from this attempt."
                        : "这次结果没有通过可靠性核对。",
                null,
                new Recovery(
                        detail,
                        english ? "Review and try again" : "检查后重试",
                        safeQuestionDraft(question)));
    }

    private static String safeQuestionDraft(String question) {
        return question;
    }

    private static boolean usableClarification(String value, PlayerLocale expectedLanguage) {
        return value != null
                && !value.isBlank()
                && PlayerLocale.forQuestion(value, expectedLanguage) == expectedLanguage;
    }

    private static SourceKind source(StructuredRuleAnswer answer) {
        if (answer.confirmedRulingId() != null) return SourceKind.CONFIRMED;
        return answer.official() ? SourceKind.OFFICIAL : SourceKind.UPLOADED;
    }

    private static String languageTag(PlayerLocale language) {
        return language == PlayerLocale.EN ? "en" : "zh-CN";
    }

    private record FailureCopy(String verdict, String clarification, Recovery recovery) {}
}
