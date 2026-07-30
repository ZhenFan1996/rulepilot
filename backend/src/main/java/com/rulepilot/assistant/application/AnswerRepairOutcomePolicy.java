package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import java.util.List;
import java.util.Optional;

/** Pure outcomes for bounded player-facing answer repair; the workflow owns all model calls and publication. */
final class AnswerRepairOutcomePolicy {

    private AnswerRepairOutcomePolicy() {}

    static String insufficientRepairMessage(List<String> playerFacingRepair) {
        return "图标对应的规则资源无法从现有证据中可靠确定。";
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

    record PublicationFailure(AnswerStatus status, String message) {}
}
