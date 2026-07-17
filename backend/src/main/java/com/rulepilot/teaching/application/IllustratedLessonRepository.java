package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import java.util.Optional;
import java.util.UUID;

public interface IllustratedLessonRepository {

    IllustratedLesson save(IllustratedLesson lesson);

    Optional<IllustratedLesson> findLatestByPlan(UUID teachingPlanId);
}
