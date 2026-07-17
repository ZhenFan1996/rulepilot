package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.RuleStructureCatalog;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeachingPlanService {

    private final RuleStructureCatalog structures;
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;

    public TeachingPlanService(
            RuleStructureCatalog structures, TeachingPlanFactory plans, TeachingPlanRepository repository) {
        this.structures = structures;
        this.plans = plans;
        this.repository = repository;
    }

    @Transactional
    public TeachingPlan create(
            UUID documentVersionId, int playerCount, int beginnerCount, int durationMinutes, String createdBy) {
        return repository.save(plans.create(
                documentVersionId,
                playerCount,
                beginnerCount,
                durationMinutes,
                createdBy,
                structures.structure(documentVersionId)));
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> latest(UUID documentVersionId) {
        return repository.findLatest(documentVersionId);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> find(UUID planId) {
        return repository.findById(planId);
    }
}
