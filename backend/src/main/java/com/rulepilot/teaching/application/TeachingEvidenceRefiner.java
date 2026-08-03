package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.UUID;

/** Stable boundary between teaching orchestration and optional native evidence acquisition. */
interface TeachingEvidenceRefiner {

    TeachingSectionEvidenceRetriever.Result refine(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result deterministic);
}
