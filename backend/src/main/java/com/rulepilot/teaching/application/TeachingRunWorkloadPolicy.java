package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.teaching.domain.TeachingPlan;

/** Estimates the ordinary useful path for workload admission; recovery remains governed by the durable run. */
final class TeachingRunWorkloadPolicy {

    private final int maxRetrievalQueriesPerSection;

    TeachingRunWorkloadPolicy(int maxRetrievalQueriesPerSection) {
        if (maxRetrievalQueriesPerSection < 1) {
            throw new IllegalArgumentException("teaching workload policy is invalid");
        }
        this.maxRetrievalQueriesPerSection = maxRetrievalQueriesPerSection;
    }

    WorkloadDemand demand(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) {
            throw new IllegalArgumentException("teaching plan needs a countable workload");
        }
        long estimatedModelCalls = Math.addExact(
                (long) plan.sections().size(),
                Math.addExact(
                        (long) TeachingVisualEvidenceResolver.estimatedCatalogModelCalls(plan),
                        VisualLessonEnricher.estimatedTeachingRunModelCalls(plan)));
        if (estimatedModelCalls > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching workload is too large");
        }
        return new WorkloadDemand((int) estimatedModelCalls);
    }

    int maxRetrievalQueriesPerSection() {
        return maxRetrievalQueriesPerSection;
    }
}
