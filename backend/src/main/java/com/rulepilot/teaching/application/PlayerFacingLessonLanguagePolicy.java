package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;

/** Uses structured publication state instead of guessing lesson quality from player-facing wording. */
public final class PlayerFacingLessonLanguagePolicy {

    private PlayerFacingLessonLanguagePolicy() {}

    public static boolean isPubliclyReadable(IllustratedLesson lesson) {
        if (lesson == null
                || lesson.status() == IllustratedLesson.LessonStatus.INCOMPLETE
                || lesson.sections().isEmpty()) {
            return false;
        }
        return lesson.sections().stream().allMatch(section ->
                section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE
                        && !section.visualSourceChunkIds().isEmpty()
                        && !section.steps().isEmpty()
                        && section.steps().stream().allMatch(step ->
                                !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty()));
    }
}
