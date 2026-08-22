package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns one bounded structured-output repair only.
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

    List<String> repairFeedback(String diagnostic, boolean hasPageImages, boolean visualLocalizationFailure) {
        List<String> feedback = new ArrayList<>(List.of(diagnostic));
        if (hasPageImages && visualLocalizationFailure) feedback.add(VISUAL_REPAIR_GUIDANCE);
        return List.copyOf(feedback);
    }

}
