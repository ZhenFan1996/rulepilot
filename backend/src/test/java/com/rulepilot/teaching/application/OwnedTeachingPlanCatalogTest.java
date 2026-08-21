package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnedTeachingPlanCatalogTest {

    @Test
    void batchesPlayerFacingLessonProgressIntoTheLightweightPlanList() {
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = mock(IllustratedLessonRepository.class);
        UUID planId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        var plan = new TeachingPlanSummary(
                planId, UUID.randomUUID(), "Game", "Premise", List.of(), null,
                "alice", Instant.parse("2026-08-21T05:00:00Z"));
        when(plans.findSummariesByCreatedBy("alice")).thenReturn(List.of(plan));
        when(lessons.findLatestProgressSummariesByPlans(List.of(planId))).thenReturn(List.of(
                new IllustratedLessonRepository.ProgressSummary(
                        lessonId, planId, LessonStatus.DRAFT_READY,
                        List.of(EvidenceStatus.CITED_DRAFT, EvidenceStatus.INSUFFICIENT_EVIDENCE))));

        var result = new OwnedTeachingPlanCatalog(plans, lessons).list("alice");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().lesson().id()).isEqualTo(lessonId);
        assertThat(result.getFirst().lesson().sections())
                .extracting(TeachingPlanSummary.SectionProgress::evidenceStatus)
                .containsExactly(EvidenceStatus.CITED_DRAFT, EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }
}
