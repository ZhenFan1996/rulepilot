package com.rulepilot.teaching.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class OwnedTeachingPlanCatalog {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;

    public OwnedTeachingPlanCatalog(TeachingPlanRepository plans, IllustratedLessonRepository lessons) {
        this.plans = plans;
        this.lessons = lessons;
    }

    @Transactional(readOnly = true)
    public List<TeachingPlanSummary> list(String ownerUsername) {
        List<TeachingPlanSummary> summaries = plans.findSummariesByCreatedBy(ownerUsername);
        Map<UUID, IllustratedLessonRepository.ProgressSummary> progressByPlan = lessons
                .findLatestProgressSummariesByPlans(summaries.stream().map(TeachingPlanSummary::id).toList())
                .stream()
                .collect(Collectors.toMap(IllustratedLessonRepository.ProgressSummary::teachingPlanId, item -> item));
        return summaries.stream()
                .map(summary -> summary.withLesson(progressByPlan.get(summary.id())))
                .toList();
    }
}
