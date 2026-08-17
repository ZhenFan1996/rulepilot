package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns bounded schema and visual fallback recovery only.
 *
 * <p>Rule meaning belongs to the Teaching model and the independent semantic Critic. This policy deliberately does
 * not inspect, append, delete, or rewrite player-facing rule prose.</p>
 */
final class TeachingDraftRecoveryPolicy {

    private static final int MAX_SCHEMA_REPAIR_ATTEMPTS = 1;
    private static final String VISUAL_REPAIR_GUIDANCE = "The attached page images are usable visual evidence. "
            + "Keep the grounded text, but repair one VISUAL step: cite an attached-page E-reference and return a "
            + "compact 0-1000 focus rectangle that contains the visible area named in that step. Do not fall back "
            + "to text-only.";

    int maxRepairAttempts(boolean hasPageImages) {
        return MAX_SCHEMA_REPAIR_ATTEMPTS;
    }

    boolean canFallbackToCitedText(boolean hasPageImages, boolean hasOnlyVisualPageEvidence) {
        return hasPageImages && !hasOnlyVisualPageEvidence;
    }

    boolean shouldFallbackToCitedText(
            boolean hasPageImages, boolean hasOnlyVisualPageEvidence, int repairAttempt) {
        return canFallbackToCitedText(hasPageImages, hasOnlyVisualPageEvidence)
                && repairAttempt == maxRepairAttempts(hasPageImages);
    }

    List<String> repairFeedback(String diagnostic, boolean hasPageImages, boolean visualLocalizationFailure) {
        List<String> feedback = new ArrayList<>(List.of(diagnostic));
        if (hasPageImages && visualLocalizationFailure) feedback.add(VISUAL_REPAIR_GUIDANCE);
        return List.copyOf(feedback);
    }

    List<String> textFallbackFeedback(String diagnostic) {
        return List.of("Return the same evidence-grounded section in the required text-only JSON schema. " + diagnostic);
    }

    TeachingLessonModel.SectionRequest withoutPageImages(TeachingLessonModel.SectionRequest request) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.priorSections(),
                request.evidence(),
                List.of(),
                request.requiredRuleIntents(),
                request.teachingUnits(),
                request.modelConfigurationOwner(),
                request.chapterScope(),
                request.wholeGameContext());
    }

}
