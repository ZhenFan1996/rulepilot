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

    Optional<TeachingPlan> findLatest(UUID documentVersionId, String createdBy);

    void delete(UUID planId);
}
