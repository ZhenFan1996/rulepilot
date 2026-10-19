package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import java.util.List;

/** Uses structured publication state instead of guessing lesson quality from player-facing wording. */
public final class PlayerFacingLessonLanguagePolicy {

    private PlayerFacingLessonLanguagePolicy() {}

    public static boolean isPubliclyReadable(IllustratedLesson lesson) {
        if (lesson == null
                || lesson.status() != IllustratedLesson.LessonStatus.COMPLETE
                || lesson.sections().isEmpty()) {
            return false;
        }
        return lesson.sections().stream().allMatch(PlayerFacingLessonLanguagePolicy::isReadableSection);
    }

    /** Public readers receive complete guides; private progress snapshots remain available only to their owner. */
    static IllustratedLesson publicProjection(IllustratedLesson lesson) {
        if (!isPubliclyReadable(lesson)) {
            throw new IllegalArgumentException("lesson has no publicly readable cited chapter");
        }
        return lesson;
    }

    private static boolean isReadableSection(LessonSection section) {
        return section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE
                && !section.visualSourceChunkIds().isEmpty()
                && !section.steps().isEmpty()
                && section.steps().stream().allMatch(step ->
                        !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty());
    }
}
