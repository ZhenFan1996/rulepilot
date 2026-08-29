package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.RuleAnswerModelUnavailableException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;
import java.util.UUID;

/**
 * Produces one prepared, evidence-backed answer draft through the model's adaptive correction loop.
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
            AnswerModelGateway.Composition composition =
                    modelGateway.compose(assistantRunId, username, gameSessionId, modelRequest);
            draft = composition.draft();
            modelRepairs = composition.replacements();
        } catch (RuleAnswerModelUnavailableException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_UNAVAILABLE,
                    "答疑模型或其配置暂时不可用；问题和规则证据本身未被拒绝。");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答生成超时，可以稍后重试或直接查看规则引用。");
        } catch (RuleAnswerModelInvalidOutputException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "答疑模型返回的完整结构或引用标识未通过校验，且没有新的可修正信息。");
        }
        return prepare(modelRequest, draft, modelRepairs);
    }

    Result continueAfterValidationRejection(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft rejectedDraft,
            RuntimeException rejection) {
        ModelDraft revised;
        int replacements;
        try {
            AnswerModelGateway.Composition composition = modelGateway.continueAfterValidationRejection(
                    assistantRunId,
                    username,
                    gameSessionId,
                    modelRequest,
                    rejectedDraft,
                    diagnostic(rejection),
                    "replaceValidationRejectedRuleAnswer",
                    "Validation-rejected answer returned as one complete replacement");
            revised = composition.draft();
            replacements = composition.replacements();
        } catch (RuleAnswerModelUnavailableException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_UNAVAILABLE,
                    "答疑模型或其配置暂时不可用，未能完成回答修订。");
        } catch (RuleAnswerModelTimeoutException exception) {
            return Result.failure(
                    AnswerStatus.MODEL_TIMEOUT,
                    "回答修订超时，可以稍后重试或直接查看规则引用。");
        } catch (RuleAnswerModelInvalidOutputException exception) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "答疑模型返回的完整修订结构或引用标识未通过校验，且没有新的可修正信息。");
        }
        return prepare(modelRequest, revised, replacements);
    }

    private Result prepare(ModelRequest modelRequest, ModelDraft draft, int replacements) {
        if (draft == null) {
            return Result.failure(
                    AnswerStatus.INVALID_MODEL_OUTPUT,
                    "答疑模型没有返回完整回答对象。");
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
        return Result.ready(preparation.draft(), preparation.warnings(), replacements);
    }

    static String diagnostic(RuntimeException rejection) {
        if (rejection == null) return "validation rejected the answer without a diagnostic";
        StringBuilder detail = new StringBuilder();
        Throwable current = rejection;
        while (current != null) {
            if (!detail.isEmpty()) detail.append(" caused by ");
            detail.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) detail.append(": ").append(message.strip());
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return detail.toString();
    }

    record Result(
            ModelDraft draft,
            List<AnswerWarning> warnings,
            AnswerStatus failureStatus,
            String failureMessage,
            int modelRepairs) {

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings) {
            return ready(draft, warnings, 0);
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
