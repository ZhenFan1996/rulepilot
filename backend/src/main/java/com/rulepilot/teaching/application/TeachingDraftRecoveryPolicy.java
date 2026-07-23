package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps draft-repair decisions deterministic while the teaching agent owns model calls and evidence validation.
 */
final class TeachingDraftRecoveryPolicy {

    private static final int MAX_TEXT_REPAIR_ATTEMPTS = 3;
    private static final String VISUAL_REPAIR_GUIDANCE = "The attached page images are usable visual evidence. "
            + "Keep the grounded text, but repair one VISUAL step: cite an attached-page E-reference and return a "
            + "compact 0-1000 focus rectangle that contains the icon, component group, board area, flow, or worked "
            + "state named in that step. Do not fall back to text-only.";

    int maxRepairAttempts(boolean hasPageImages) {
        return hasPageImages ? 1 : MAX_TEXT_REPAIR_ATTEMPTS;
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
        if (!hasPageImages || !visualLocalizationFailure) return List.of(diagnostic);
        return List.of(diagnostic, VISUAL_REPAIR_GUIDANCE);
    }

    List<String> textFallbackFeedback(String diagnostic) {
        return List.of("Keep this section text-only and preserve all grounded rule coverage. " + diagnostic);
    }

    TeachingLessonModel.SectionRequest withoutPageImages(TeachingLessonModel.SectionRequest request) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.playerCount(),
                request.beginnerCount(),
                request.totalDurationMinutes(),
                request.sectionDurationSeconds(),
                request.maxSteps(),
                request.priorSections(),
                request.evidence(),
                List.of(),
                request.requiredRuleIntents(),
                request.modelConfigurationOwner(),
                request.chapterScope());
    }

    SectionDraft preserveTextOnlyPresentationMetadata(SectionDraft previous, SectionDraft revised) {
        if (previous == null || revised == null) return revised;
        String caption = revised.visualCaption();
        if (caption == null || caption.isBlank() || caption.length() > 240) {
            caption = previous.visualCaption();
        }
        List<UUID> citations = revised.visualCitationIds();
        if (citations == null || citations.isEmpty()) {
            citations = previous.visualCitationIds();
        }
        VisualKind visualKind = revised.visualKind() == null ? previous.visualKind() : revised.visualKind();
        if (Objects.equals(caption, revised.visualCaption())
                && Objects.equals(citations, revised.visualCitationIds())
                && visualKind == revised.visualKind()) {
            return revised;
        }
        return new SectionDraft(revised.title(), visualKind, caption, citations, revised.steps());
    }
}
