package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;
import java.util.Optional;
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
        if (!draft.answerable()) {
            draft = reconsiderEvidenceBackedAbstention(
                    assistantRunId, username, gameSessionId, modelRequest, draft);
        }
        if (!draft.answerable()) {
            return Result.failure(
                    AnswerStatus.INSUFFICIENT_EVIDENCE,
                    "现有证据未能直接回答这个问题。");
        }
        draft = AnswerDraftPublicationPolicy.removePeripheralEndgameCitations(modelRequest, draft);
        List<String> playerFacingRepair = AnswerPlayerFacingRepairPolicy.feedbackFor(modelRequest, draft);
        if (!playerFacingRepair.isEmpty()) {
            try {
                draft = revisePlayerFacingDraft(
                        assistantRunId, username, gameSessionId, modelRequest, draft, playerFacingRepair);
            } catch (RuleAnswerModelTimeoutException exception) {
                return Result.failure(
                        AnswerStatus.MODEL_TIMEOUT,
                        "视觉规则消歧超时，可以稍后重试或直接查看规则引用。");
            } catch (RuntimeException exception) {
                return Result.failure(
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "回答修订结果未通过结构校验。");
            }
            if (draft == null || !draft.answerable()) {
                return Result.failure(
                        AnswerStatus.INSUFFICIENT_EVIDENCE,
                        AnswerRepairOutcomePolicy.insufficientRepairMessage(playerFacingRepair));
            }
            draft = AnswerDraftSafetyPolicy.normalizeSingleMappedVisualGlyph(
                    draft, AnswerVisualEvidencePolicy.resolvedComponents(modelRequest, draft));
            draft = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(draft);
            draft = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(draft);
            Optional<AnswerRepairOutcomePolicy.PublicationFailure> failure =
                    AnswerRepairOutcomePolicy.publicationFailure(modelRequest, draft);
            if (failure.isPresent()) {
                return Result.failure(failure.get().status(), failure.get().message());
            }
        }
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(modelRequest, draft);
        if (!preparation.ready()) {
            return Result.failure(preparation.failureStatus(), preparation.failureMessage());
        }
        return Result.ready(preparation.draft(), preparation.warnings());
    }

    private ModelDraft reconsiderEvidenceBackedAbstention(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft) {
        return modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                AnswerEvidenceReconsiderationPolicy.feedbackFor(modelRequest),
                "reconsiderEvidenceBackedAbstention",
                "Evidence-backed table abstention reconsidered");
    }

    private ModelDraft revisePlayerFacingDraft(
            UUID assistantRunId,
            String username,
            UUID gameSessionId,
            ModelRequest modelRequest,
            ModelDraft previousDraft,
            List<String> feedback) {
        return modelGateway.revise(
                assistantRunId,
                username,
                gameSessionId,
                modelRequest,
                previousDraft,
                feedback,
                "repairPlayerFacingRuleAnswer",
                "Ambiguous visual identity or internal evidence language repaired");
    }

    record Result(
            ModelDraft draft, List<AnswerWarning> warnings, AnswerStatus failureStatus, String failureMessage) {

        static Result ready(ModelDraft draft, List<AnswerWarning> warnings) {
            return new Result(draft, List.copyOf(warnings), null, null);
        }

        static Result failure(AnswerStatus status, String message) {
            return new Result(null, List.of(), status, message);
        }

        boolean ready() {
            return draft != null;
        }
    }
}
