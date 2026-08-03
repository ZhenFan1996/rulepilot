package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Set;
import java.util.stream.Collectors;

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
        // A model-created objective cannot bootstrap its own authority. Empty chapters may be recovered only when
        // the validated plan already binds the need to source-derived page coordinates.
        if (deterministic.state() == TeachingSectionEvidenceRetriever.State.EMPTY) {
            return !planned.sourcePageNumbers().isEmpty();
        }
        if (planned.sourcePageNumbers().isEmpty()) return false;
        Set<Integer> evidencedPages = deterministic.evidence().stream()
                .flatMap(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo()).boxed())
                .collect(Collectors.toSet());
        return !evidencedPages.containsAll(planned.sourcePageNumbers());
    }
}
