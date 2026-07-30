package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.AnswerWarning.Type;
import java.util.List;
import java.util.UUID;

/** Pure final-draft preparation before a validated answer can be published to a player. */
final class AnswerDraftPublicationPolicy {

    private static final String MISSING_ENDGAME_RESOLUTION = "回答没有引用游戏结束结算的直接规则依据。";

    private AnswerDraftPublicationPolicy() {}

    static ModelDraft removePeripheralEndgameCitations(ModelRequest request, ModelDraft draft) {
        if (!AnswerEvidencePolicy.isEndgameTimingAndTieSummary(request.question(), request.evidence())
                || draft.citationIds().isEmpty()) return draft;
        List<UUID> decisive = AnswerEvidencePolicy.requiredEndgameCitationIds(
                request.question(), request.evidence(), draft.citationIds());
        if (decisive.isEmpty() || decisive.size() == draft.citationIds().size()) return draft;
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                decisive,
                draft.exceptions(),
                draft.confidence(),
                draft.answerBasis());
    }

    static Preparation prepare(ModelRequest request, ModelDraft draft) {
        ModelDraft prepared = removePeripheralEndgameCitations(request, draft);
        prepared = AnswerSpatialScopePolicy.boundRepeatedInference(request, prepared);
        prepared = AnswerBasisPolicy.classify(request, prepared);
        if (AnswerEvidencePolicy.requiresEndgameResolutionCitation(request.question(), request.evidence())
                && !AnswerEvidencePolicy.citesEndgameResolution(
                        request.question(), request.evidence(), prepared.citationIds())) {
            return Preparation.rejected(MISSING_ENDGAME_RESOLUTION);
        }
        if (AnswerConditionEvidencePolicy.needsDirectCitation(request, prepared.citationIds())) {
            return Preparation.readyWithWarning(
                    AnswerVisualEvidencePolicy.includeReferenceCitations(request, prepared),
                    new AnswerWarning(Type.INDIRECT_CITATION));
        }
        return Preparation.ready(AnswerVisualEvidencePolicy.includeReferenceCitations(request, prepared));
    }

    record Preparation(
            ModelDraft draft, List<AnswerWarning> warnings, AnswerStatus failureStatus, String failureMessage) {

        static Preparation ready(ModelDraft draft) {
            return new Preparation(draft, List.of(), null, null);
        }

        static Preparation readyWithWarning(ModelDraft draft, AnswerWarning warning) {
            return new Preparation(draft, List.of(warning), null, null);
        }

        static Preparation rejected(String failureMessage) {
            return new Preparation(null, List.of(), AnswerStatus.INVALID_MODEL_OUTPUT, failureMessage);
        }

        boolean ready() {
            return failureStatus == null;
        }
    }
}
