package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeachingPlanRepository {

    TeachingPlan save(TeachingPlan plan);

    Optional<TeachingPlan> findById(UUID planId);

    Optional<TeachingPlan> findByIdAndCreatedBy(UUID planId, String createdBy);

    List<TeachingPlan> findAllByCreatedBy(String createdBy);

    List<TeachingPlan> findRecent(int limit);

    /**
     * Lightweight newest-first references for discovery views.  Callers that only need the public catalog
     * must not hydrate every section of every candidate teaching plan.
     */
    List<PlanReference> findRecentReferences(int limit);

    Optional<TeachingPlan> findLatest(UUID documentVersionId, String createdBy);

    void delete(UUID planId);

    record PlanReference(UUID teachingPlanId, UUID documentVersionId, String gameTitle) {
        public PlanReference {
            if (teachingPlanId == null || documentVersionId == null || gameTitle == null || gameTitle.isBlank()) {
                throw new IllegalArgumentException("teaching plan reference is invalid");
            }
            gameTitle = gameTitle.strip();
        }
    }
}
