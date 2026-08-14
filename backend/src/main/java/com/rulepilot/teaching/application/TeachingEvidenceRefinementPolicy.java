package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
/** Game-independent coverage policy derived only from the validated plan and canonical evidence coordinates. */
final class TeachingEvidenceRefinementPolicy {

    private TeachingEvidenceRefinementPolicy() {}

    static boolean requiresRefinement(
            TeachingPlan.PlannedSection planned,
            TeachingSectionEvidenceRetriever.Result deterministic) {
        if (planned == null || deterministic == null
                || deterministic.state() == TeachingSectionEvidenceRetriever.State.INVALID) {
            return false;
        }
        // Search relevance is not proof of page completeness: one hit near a page heading can omit later setup
        // requirements, exceptions, or tie breakers. Only validated plan coordinates may authorize this bounded read.
        return !planned.sourcePageNumbers().isEmpty();
    }
}
