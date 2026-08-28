package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.teaching.domain.TeachingPlan;

/** Counts the complete bounded call graph before a Teaching run receives its durable execution budget. */
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
        long toolCalls = 0;
        for (TeachingPlan.PlannedSection section : plan.sections()) {
            toolCalls += TeachingSectionEvidenceRetriever.maximumToolCalls(
                    section, maxRetrievalQueriesPerSection);
            toolCalls += TeachingSourcePageEvidenceRefiner.maximumToolCalls(section);
        }
        long modelCalls = Math.addExact(
                Math.multiplyExact((long) plan.sections().size(), TeachingModelCallBudget.maximumSectionCalls()),
                Math.addExact(
                        (long) TeachingPublishedLessonReviewer.maximumModelCalls(plan.sections().size()),
                        Math.addExact(
                                (long) TeachingVisualEvidenceResolver.maximumModelCalls(plan),
                                VisualLessonEnricher.maximumTeachingRunModelCalls(plan))));
        if (toolCalls > Integer.MAX_VALUE || modelCalls > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching workload is too large");
        }
        return new WorkloadDemand((int) toolCalls, (int) modelCalls);
    }

    int maxRetrievalQueriesPerSection() {
        return maxRetrievalQueriesPerSection;
    }
}
