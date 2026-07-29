package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IllustratedLessonRepository {

    IllustratedLesson save(IllustratedLesson lesson);

    Optional<IllustratedLesson> findLatestByPlan(UUID teachingPlanId);

    /**
     * Summaries used by a catalog card. They deliberately avoid handing full lesson prose to the discovery
     * endpoint while preserving the same player-facing language eligibility rule as a full public lesson.
     */
    List<LessonSummary> findLatestSummariesByPlans(Collection<UUID> teachingPlanIds);

    record LessonSummary(UUID teachingPlanId, IllustratedLesson.LessonStatus status, boolean publiclyReadable,
                         int sectionCount, int stepCount) {
        public LessonSummary {
            if (teachingPlanId == null || status == null || sectionCount < 0 || stepCount < 0) {
                throw new IllegalArgumentException("lesson summary is invalid");
            }
        }
    }
}
