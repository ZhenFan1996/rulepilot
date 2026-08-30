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
                || lesson.status() == IllustratedLesson.LessonStatus.INCOMPLETE
                || lesson.sections().isEmpty()) {
            return false;
        }
        return lesson.sections().stream().anyMatch(PlayerFacingLessonLanguagePolicy::isReadableSection);
    }

    /** Returns a reader-only projection; the stored lesson and its insufficient chapters remain unchanged. */
    static IllustratedLesson publicProjection(IllustratedLesson lesson) {
        if (!isPubliclyReadable(lesson)) {
            throw new IllegalArgumentException("lesson has no publicly readable cited chapter");
        }
        List<LessonSection> citedSections = lesson.sections().stream()
                .filter(PlayerFacingLessonLanguagePolicy::isReadableSection)
                .toList();
        return new IllustratedLesson(
                lesson.id(),
                lesson.teachingPlanId(),
                lesson.status(),
                citedSections,
                lesson.generatorVersion(),
                lesson.createdAt());
    }

    private static boolean isReadableSection(LessonSection section) {
        return section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE
                && !section.visualSourceChunkIds().isEmpty()
                && !section.steps().isEmpty()
                && section.steps().stream().allMatch(step ->
                        !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty());
    }
}
