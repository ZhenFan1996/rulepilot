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
            ModelDraft rejectedDraft,
            RuntimeException rejection) {
        ModelDraft revised;
        try {
            revised = modelGateway.revise(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    List.of(
                            "The final schema or citation validator rejected the previous answer: "
                                    + boundedDiagnostic(rejection),
                            "Return one COMPLETE replacement answer using only the supplied evidence and its typed citationIds.",
                            "Correct the stated validation failure, keep every player-facing rule claim directly supported, and preserve independently valid meaning where the evidence allows it.",
                            "Do not return a field patch and do not rely on the application to combine this response with the rejected answer."),
                    "repairPublicationValidation",
                    "Final citation validation returned as one complete replacement");
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

    private String boundedDiagnostic(RuntimeException rejection) {
        if (rejection == null) return "validation rejected the answer without a diagnostic";
        String message = rejection.getMessage();
        String diagnostic = rejection.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message.strip());
        return diagnostic.length() <= 600 ? diagnostic : diagnostic.substring(0, 600);
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
