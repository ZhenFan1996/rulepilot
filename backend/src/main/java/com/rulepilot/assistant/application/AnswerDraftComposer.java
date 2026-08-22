package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;
import java.util.UUID;

/**
 * Produces one prepared, evidence-backed answer draft through the bounded model and repair workflow.
 *
 * <p>It cannot retrieve evidence, publish an answer, cache a result, or ask the post-publication Critic.</p>
 */
final class AnswerDraftComposer {

    private final AnswerModelGateway modelGateway;

    AnswerDraftComposer(AnswerModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    Result compose(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest) {
        ModelDraft draft;
        int modelRepairs = 0;
        try {
            draft = modelGateway.compose(assistantRunId, username, gameSessionId, modelRequest);
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答生成结果未通过结构或引用校验。");
        }
        if (draft == null) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答生成结果未通过结构或引用校验。");
        }
        draft = AnswerStructuredDraftPolicy.retainSelected(modelRequest, draft).draft();
        if (!draft.answerable()) {
            return Result.failure(
                    AnswerStatus.INSUFFICIENT_EVIDENCE,
                    "现有证据未能直接回答这个问题。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, draft);
        if (!preparation.ready()) {
            return Result.failure(preparation.failureStatus(), preparation.failureMessage());
        }
        return Result.ready(preparation.draft(), preparation.warnings(), modelRepairs);
    }

    Result repairAfterPublicationFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The draft failed the final schema or citation validation.",
                            "Return a complete answer using only citationIds present in supplied evidence.",
                            "Keep shortVerdict and explanation nonblank, natural, and detailed enough to apply at the table; keep citationIds nonempty.",
                            "Preserve structured fields that already satisfy their schema and evidence contract unless they caused the stated validation failure.",
                            "For a SOURCE request or a question asking where the rule appears, use one or two direct citationIds, keep the most direct clause first, answer the question in shortVerdict, and explain that clause in plain player language instead of redirecting to a page.",
                            "For a SOURCE request, do not add ownership or hand location, and do not change 'during a turn or phase' into 'at the end or completion of' that turn or phase unless the cited clause says so.",
                            "For a SOURCE request, preserve the exact grammatical number of every capitalized official term from the cited clause; do not invent a singular or plural form.",
                            "For a SOURCE request, do not claim that the rule or excerpt has no other restriction, condition, exception, limit, or exact timing; explain only what it affirmatively states.",
                            "For a can/may/allowed question, answer can or cannot directly in shortVerdict and preserve the cited permission or prohibition direction, including every stated condition and exception.",
                            "Remove every claim that the cited excerpts do not directly support."),
                    "repairPublicationValidation",
                    "Final citation validation failure repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答修订结果未通过结构校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "回答修订结果未通过结构或引用校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation =
                AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }

    Result repairAfterCalculationFailure(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "A requested arithmetic derivation used an unsupported operand or invalid expression.",
                            "Use only +, -, *, /, parentheses, floor, ceil, min, or max.",
                            "Every numeric operand must appear in the current player question or cited evidence, and at least one operand must come from the current question.",
                            "If no grounded arithmetic is needed, return an empty calculations list and remove unsupported computed totals."),
                    "repairRuleCalculation",
                    "Unsupported rule calculation repaired");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(AnswerStatus.MODEL_TIMEOUT, "规则计算修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuntimeException exception) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则计算未通过输入来源或表达式校验。");
        }
        if (revised == null || !revised.answerable()) {
            return Result.failure(AnswerStatus.INVALID_MODEL_OUTPUT, "规则计算未通过输入来源或表达式校验。");
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, revised);
        return preparation.ready()
                ? Result.ready(preparation.draft(), preparation.warnings())
                : Result.failure(preparation.failureStatus(), preparation.failureMessage());
    }


    record Result(
            ModelDraft draft,
            List<AnswerWarning> warnings,
            AnswerStatus failureStatus,
            String failureMessage,
            int modelRepairs) {

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings) {
            return ready(draft, warnings, 1);
        }

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings, int modelRepairs) {
            return new Result(draft, List.copyOf(warnings), null, null, modelRepairs);
        }

        static Result failure(AnswerStatus status, String message) {
            return new Result(null, List.of(), status, message, 0);
        }

        boolean ready() {
            return draft != null;
        }
    }
}
