package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.ArrayList;
import java.util.List;

/** Pure checks that decide whether an otherwise answerable draft needs a player-facing repair. */
final class AnswerPlayerFacingRepairPolicy {

    private AnswerPlayerFacingRepairPolicy() {}

    static List<String> feedbackFor(ModelRequest request, ModelDraft draft) {
        List<String> feedback = new ArrayList<>();
        if (AnswerEvidencePolicy.requiresEndTurnProcedureCitation(request.question(), request.evidence())
                && !AnswerEvidencePolicy.citesEndTurnProcedure(request.evidence(), draft.citationIds())) {
            feedback.add("END_TURN_PROCEDURE_CITATION: The question asks what happens after a player finishes a turn. "
                    + "Cite the supplied excerpt that explicitly connects turn end to drawing, revealing, reading, "
                    + "resolving, or executing the event/card effect. Setup instructions that only place that deck "
                    + "or card area are not sufficient evidence for the end-of-turn procedure.");
        }
        if (AnswerEvidencePolicy.requiresEndgameResolutionCitation(request.question(), request.evidence())
                && !AnswerEvidencePolicy.citesEndgameResolution(request.question(), request.evidence(), draft.citationIds())) {
            feedback.add("ENDGAME_RESOLUTION_CITATION: The question asks about an end trigger, end-of-round timing, "
                    + "final scoring, winner, or tie. Cite the supplied excerpt that states the actual end-game "
                    + "condition and resolution sequence. A component or inventory excerpt that merely names a "
                    + "marker, card, or resource cannot support that timing, scoring, or tie ruling. Preserve the "
                    + "printed order, including any numbered cleanup check, and do not invent a separate phase.");
        }
        if (AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(request, draft)) {
            feedback.add("EXACT_PHASE_NAME: The draft uses an English multi-word phase label that does not appear in "
                    + "its cited excerpts. Remove it rather than inventing a phase. If the source has a numbered "
                    + "end-game check within cleanup, state that evidenced check and its order directly.");
        }
        if (AnswerVisualEvidencePolicy.requiresIdentityReconciliation(request, draft)) {
            feedback.add("VISUAL_IDENTITY: The draft relies on an unresolved icon, color, shape, emoji, or guessed "
                    + "resource name. Reconcile every supplied page before answering. Do not reject an entire visual "
                    + "fact merely because it also transcribes an icon glyph. A mapping is resolved when the evidence "
                    + "explicitly says the operational icon is visually identical to an exact printed component label "
                    + "on another supplied page and that labeled page is also supplied. Treat a name as an untrusted "
                    + "guess only when it is based solely on emoji, color, shape, 'likely', or '可能'. Then verify "
                    + "the mapping against starting quantities, public/hidden placement, thresholds, and worked "
                    + "arithmetic. Cite both the labeled page and operational page. Use only the printed component "
                    + "term in player-facing text and emit no icon glyph. If one mapping is not directly supportable, "
                    + "set answerable to false.");
        }
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(draft)) {
            feedback.add("PLAYER_FACING_OUTPUT: Remove UUIDs, chunk IDs, E-number evidence labels, retrieval wording, "
                    + "and other internal references. Teach the rule directly while preserving the same citations in "
                    + "the structured citationIds field.");
        }
        if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)
                && AnswerDraftSafetyPolicy.containsResourceCardConflation(draft)) {
            feedback.add("RESOURCE_CARD_CONFLATION: Cross-page evidence maps the missing inline icon to a named "
                    + "token, point, or other component. Remove every claim that turns that same icon prerequisite "
                    + "into cards in hand, a minimum card count, or a numeric card value. A consequence that the "
                    + "player starts the phase with fewer cards is separate from the component required to activate "
                    + "the effect. Keep only prerequisites directly stated by the supplied evidence.");
        }
        List<String> resolvedComponents = AnswerVisualEvidencePolicy.resolvedComponents(request, draft);
        if (!resolvedComponents.isEmpty()
                && !AnswerVisualEvidencePolicy.namesEveryResolvedComponent(request, draft)) {
            feedback.add("RESOLVED_VISUAL_COMPONENT: The supplied cross-page visual evidence explicitly resolves "
                    + "the operational icon to these original-language component labels: " + resolvedComponents
                    + ". The shortVerdict must include each applicable exact label in its original language, alongside "
                    + "a faithful Chinese translation if useful. Do not negate that mapped label or replace it with "
                    + "another component from the reference page.");
        }
        if (AnswerVisualEvidencePolicy.hasEvidencedCrossPageIconMapping(request)
                && AnswerDraftSafetyPolicy.containsVisualGlyph(draft)) {
            feedback.add("MAPPED_COMPONENT_GLYPH: Remove every emoji or improvised symbol from the player-facing "
                    + "answer. A visually similar glyph can depict a different rulebook component. Use only the "
                    + "exact printed component label resolved by the supplied cross-page evidence; the UI will "
                    + "show the original cited page image.");
        }
        if (AnswerDraftSafetyPolicy.containsInactiveActorContinuation(draft)) {
            feedback.add("INACTIVE_ACTOR: The draft says an actor has emptied their hand or left active play, but "
                    + "also assigns that same actor the next action. Do not apply the default next-actor rule across "
                    + "that state change. Use the supplied evidence's explicit replacement, skip, or successor rule; "
                    + "if the evidence does not resolve the successor, set answerable to false.");
        }
        if (AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(request, draft)) {
            feedback.add("UNASKED_REPEATABILITY: The answer adds a once-only, twice-only, repeatability, or "
                    + "loop-prevention restriction that the player did not ask about and the draft's own cited "
                    + "evidence does not establish for this ruling. Remove that peripheral restriction. Keep a "
                    + "repeatability boundary only when the question asks about it or cite the exact evidence that "
                    + "governs the same action.");
        }
        if (AnswerSpatialScopePolicy.needsRepair(request, draft)) {
            feedback.add("SPATIAL_SCOPE: The player gave one board position, but the draft adds a different row, "
                    + "column, or geometric restriction not stated in that question. Do not infer that a marker "
                    + "beside one row or column blocks neighbouring rows or columns. Keep only the exact printed "
                    + "restriction. If the answer depends on a physical placement the supplied evidence does not "
                    + "specify, say what the player must confirm instead of naming extra coordinates.");
        }
        return List.copyOf(feedback);
    }
}
