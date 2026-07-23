package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure outcomes for bounded player-facing answer repair; the workflow owns all model calls and publication. */
final class AnswerRepairOutcomePolicy {

    private static final String EVIDENCED_SUCCESSOR_RULE = "EVIDENCED_SUCCESSOR_RULE: The supplied evidence explicitly "
            + "contains both the state-change condition and its replacement or successor actor. Apply that exact "
            + "conditional rule directly; do not abstain and do not fall back to the default actor.";

    private AnswerRepairOutcomePolicy() {}

    static boolean shouldRetryWithEvidencedSuccessor(
            ModelRequest request, ModelDraft repairedDraft, List<String> playerFacingRepair) {
        return (repairedDraft == null || !repairedDraft.answerable())
                && hasInactiveActorRepair(playerFacingRepair)
                && AnswerDraftSafetyPolicy.hasEvidencedSuccessorRule(request);
    }

    static ModelDraft retryDraft(ModelDraft repairedDraft) {
        return repairedDraft == null
                ? new ModelDraft(false, "First repair did not produce a draft", null, null, List.of(), List.of(), "LOW")
                : repairedDraft;
    }

    static List<String> successorRetryFeedback(List<String> playerFacingRepair) {
        List<String> feedback = new ArrayList<>(playerFacingRepair);
        feedback.add(EVIDENCED_SUCCESSOR_RULE);
        return List.copyOf(feedback);
    }

    static String insufficientRepairMessage(List<String> playerFacingRepair) {
        return hasInactiveActorRepair(playerFacingRepair)
                ? "现有证据未能确定状态变化后的下一位行动者。"
                : "图标对应的规则资源无法从现有证据中可靠确定。";
    }

    static Optional<PublicationFailure> publicationFailure(ModelRequest request, ModelDraft draft) {
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。"));
        }
        if (AnswerDraftSafetyPolicy.containsResourceCardConflation(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答混淆了规则资源与手牌数量，未向玩家发布。"));
        }
        if (AnswerDraftSafetyPolicy.containsInactiveActorContinuation(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答让已退出当前流程的玩家继续行动，未向玩家发布。"));
        }
        if (AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(request, draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答附加了未被引用支持的次数限制，未向玩家发布。"));
        }
        if (!AnswerVisualEvidencePolicy.namesEveryResolvedComponent(request, draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答未使用视觉证据确认的组件名称，未向玩家发布。"));
        }
        if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)
                && AnswerDraftSafetyPolicy.containsVisualGlyph(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INVALID_MODEL_OUTPUT, "回答用近似符号替代了规则书组件名称，未向玩家发布。"));
        }
        if (AnswerVisualEvidencePolicy.requiresIdentityReconciliation(request, draft)
                && AnswerDraftSafetyPolicy.containsUnresolvedVisualSymbol(draft)) {
            return Optional.of(new PublicationFailure(
                    AnswerStatus.INSUFFICIENT_EVIDENCE, "图标对应的规则资源无法从现有证据中可靠确定。"));
        }
        return Optional.empty();
    }

    private static boolean hasInactiveActorRepair(List<String> playerFacingRepair) {
        return playerFacingRepair.stream().anyMatch(item -> item.startsWith("INACTIVE_ACTOR:"));
    }

    record PublicationFailure(AnswerStatus status, String message) {}
}
