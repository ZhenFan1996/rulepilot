package com.rulepilot.assistant.application;

import com.rulepilot.assistant.PlayerLocale;
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
        PlayerLocale language = requestedLanguage == null ? PlayerLocale.ZH_CN : requestedLanguage;
        List<Citation> citations = answer.citations().stream()
                .map(citation -> new Citation(
                        citation.heading(),
                        citation.excerpt(),
                        citation.pageFrom(),
                        citation.pageTo()))
                .toList();
        if (answer.status() == AnswerStatus.CLARIFICATION_REQUIRED) {
            return new PlayerFacingRuleAnswer(
                    languageTag(language),
                    answer.status(),
                    answer.shortVerdict(),
                    answer.explanation() == null ? "" : answer.explanation(),
                    List.of(),
                    List.of(),
                    AnswerConfidence.LOW,
                    null,
                    source(answer),
                    answer.clarification(),
                    null,
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
        if (!answer.status().publishesConclusion()) {
            List<Citation> safeSources = answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE
                    ? citations
                    : List.of();
            return safeFailure(answer, language, question, safeSources, source(answer));
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
                answer.clarification(),
                null,
                answer.warnings(),
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

    private static PlayerFacingRuleAnswer safeFailure(
            StructuredRuleAnswer answer,
            PlayerLocale language,
            String question,
            List<Citation> citations,
            SourceKind source) {
        AnswerStatus status = answer.status();
        FailureCopy copy = failureCopy(status, language, question, answer.clarification());
        return new PlayerFacingRuleAnswer(
                languageTag(language),
                status,
                answer.shortVerdict().isBlank() ? copy.verdict() : answer.shortVerdict(),
                answer.explanation() == null ? "" : answer.explanation(),
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
            String detail = usableClarification(clarification)
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
                            english ? "I mean: " : "我指的是：",
                            false));
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
                    new Recovery(detail, english ? "Add detail" : "补充细节", "", false));
        }
        if (status == AnswerStatus.RETRIEVAL_UNAVAILABLE) {
            String detail = english
                    ? "The question was not rejected and the answer model was not called. Retry the same question unchanged after rule search recovers."
                    : "问题本身没有被拒绝，答疑模型也尚未调用；规则检索恢复后可以原样重试同一个问题。";
            return new FailureCopy(
                    english
                            ? "Rule search was temporarily unavailable, so no answer was generated."
                            : "规则检索暂时不可用，因此这次尚未生成回答。",
                    null,
                    new Recovery(
                            detail,
                            english ? "Reuse the same question" : "保留原问题",
                            safeQuestionDraft(question),
                            true));
        }
        if (status == AnswerStatus.MODEL_UNAVAILABLE) {
            String detail = english
                    ? "The question and rule sources were not rejected. Once the answer model is available, it is appropriate to retry the same question unchanged."
                    : "问题和规则依据本身没有被拒绝；答疑模型恢复后，适合原样重试同一个问题。";
            return new FailureCopy(
                    english
                            ? "No answer model or provider was available for this request."
                            : "这次请求没有可用的答疑模型或模型提供方。",
                    null,
                    new Recovery(
                            detail,
                            english ? "Reuse the same question" : "保留原问题",
                            safeQuestionDraft(question),
                            true));
        }
        if (status == AnswerStatus.MODEL_TIMEOUT) {
            String detail = english
                    ? "The timeout does not mean the question was invalid. It is appropriate to retry the same question unchanged."
                    : "超时不代表问题无效；适合原样重试同一个问题。";
            return new FailureCopy(
                    english
                            ? "This answer did not finish before the request's time limit."
                            : "这次答疑没有在本次请求时限内完成。",
                    null,
                    new Recovery(
                            detail,
                            english ? "Reuse the same question" : "保留原问题",
                            safeQuestionDraft(question),
                            true));
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
                            safeQuestionDraft(question),
                            false));
        }
        String detail = english
                ? "The question itself was not rejected. The same Agent stopped after a complete response or action failed its typed JSON, evidence-identity, or no-progress boundary."
                : "问题本身没有被拒绝；同一 Agent 是在完整回复或动作触发 typed JSON、证据身份或无进展边界后停止的。";
        return new FailureCopy(
                english
                        ? "The answer Agent stopped at a typed publication boundary."
                        : "答疑 Agent 已在 typed 发布边界停止。",
                null,
                new Recovery(
                        detail,
                        english ? "Review Agent result" : "查看 Agent 结果",
                        safeQuestionDraft(question),
                        false));
    }

    private static String safeQuestionDraft(String question) {
        return question;
    }

    private static boolean usableClarification(String value) {
        return value != null && !value.isBlank();
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
