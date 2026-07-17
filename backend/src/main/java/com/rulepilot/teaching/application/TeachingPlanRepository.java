package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Optional;
import java.util.UUID;

public interface TeachingPlanRepository {

    TeachingPlan save(TeachingPlan plan);

    Optional<TeachingPlan> findLatest(UUID documentVersionId);
}
