package com.rulepilot.teaching.adapter.out.persistence;

import com.rulepilot.teaching.domain.TeachingPlan;

/** Exercises the production entity JSON mapping in paid canaries when a PostgreSQL daemon is unavailable. */
public final class TeachingPlanPersistenceRoundTrip {

    private TeachingPlanPersistenceRoundTrip() {}

    public static TeachingPlan serializeAndReload(TeachingPlan plan) {
        TeachingPlanEntity entity = new TeachingPlanEntity(plan);
        return entity.toDomain(plan.sections());
    }
}
