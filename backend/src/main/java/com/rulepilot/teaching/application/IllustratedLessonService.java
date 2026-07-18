package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class IllustratedLessonService {

    private final TeachingPlanRepository plans;
    private final GroundedTeachingAgent agent;
    private final IllustratedLessonRepository repository;

    public IllustratedLessonService(
            TeachingPlanRepository plans,
            GroundedTeachingAgent agent,
            IllustratedLessonRepository repository) {
        this.plans = plans;
        this.agent = agent;
        this.repository = repository;
    }

    @Transactional
    public IllustratedLesson create(UUID teachingPlanId) {
        var plan = plans.findById(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        return repository.save(agent.create(plan));
    }

    @Transactional(readOnly = true)
    public Optional<IllustratedLesson> latest(UUID teachingPlanId) {
        return repository.findLatestByPlan(teachingPlanId);
    }
}
