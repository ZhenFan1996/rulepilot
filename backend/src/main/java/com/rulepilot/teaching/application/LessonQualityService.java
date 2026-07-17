package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.LessonQualityReport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class LessonQualityService {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final LessonQualityEvaluator evaluator;

    public LessonQualityService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            LessonQualityEvaluator evaluator) {
        this.plans = plans;
        this.lessons = lessons;
        this.evaluator = evaluator;
    }

    @Transactional(readOnly = true)
    public LessonQualityReport evaluateLatest(UUID teachingPlanId) {
        var plan = plans.findById(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        var lesson = lessons.findLatestByPlan(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
        return evaluator.evaluate(plan, lesson);
    }
}
