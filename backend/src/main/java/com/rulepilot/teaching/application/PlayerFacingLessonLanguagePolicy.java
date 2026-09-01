package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
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
        var readableSections = publiclyReadableSections(lesson);
        return !readableSections.isEmpty()
                && readableSections.stream().allMatch(section ->
                        !section.visualSourceChunkIds().isEmpty()
                        && !section.steps().isEmpty()
                        && section.steps().stream().allMatch(step ->
                                !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty()));
    }

    static List<IllustratedLesson.LessonSection> publiclyReadableSections(IllustratedLesson lesson) {
        if (lesson == null) return List.of();
        return lesson.sections().stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .toList();
    }
}
