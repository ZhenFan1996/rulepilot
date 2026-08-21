package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IllustratedLessonRepository {

    IllustratedLesson save(IllustratedLesson lesson);

    /** Stores a generated lesson without changing the lesson currently visible to players. */
    IllustratedLesson saveCandidate(IllustratedLesson lesson);

    Optional<IllustratedLesson> findLatestByPlan(UUID teachingPlanId);

    Optional<IllustratedLesson> findLatestCandidateByPlan(UUID teachingPlanId);

    void promoteCandidate(UUID teachingPlanId, UUID candidateLessonId);

    void archiveCandidate(UUID teachingPlanId, UUID candidateLessonId);

    /**
     * Summaries used by a catalog card. They deliberately avoid handing full lesson prose to the discovery
     * endpoint while preserving the same player-facing language eligibility rule as a full public lesson.
     */
    List<LessonSummary> findLatestSummariesByPlans(Collection<UUID> teachingPlanIds);

    List<ProgressSummary> findLatestProgressSummariesByPlans(Collection<UUID> teachingPlanIds);

    record LessonSummary(UUID teachingPlanId, IllustratedLesson.LessonStatus status, boolean publiclyReadable,
                         int sectionCount, int stepCount) {
        public LessonSummary {
            if (teachingPlanId == null || status == null || sectionCount < 0 || stepCount < 0) {
                throw new IllegalArgumentException("lesson summary is invalid");
            }
        }
    }

    record ProgressSummary(UUID id, UUID teachingPlanId, IllustratedLesson.LessonStatus status,
                           List<IllustratedLesson.EvidenceStatus> evidenceStatuses) {
        public ProgressSummary {
            if (id == null || teachingPlanId == null || status == null || evidenceStatuses == null) {
                throw new IllegalArgumentException("lesson progress summary is invalid");
            }
            evidenceStatuses = List.copyOf(evidenceStatuses);
        }
    }
}
