package com.rulepilot.teaching.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.ingestion.RulebookUnderstandingRebuilder;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VisualLessonEnrichmentServiceTest {

    @Test
    void rebuilds_missing_layout_evidence_once_before_retrying_visual_enrichment() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(enricher.enrich(documentVersionId, lesson, "owner"))
                .thenThrow(new IllegalArgumentException("rulebook understanding does not exist"))
                .thenReturn(lesson);

        new VisualLessonEnrichmentService(plans, lessons, enricher, publisher, rebuilder).enrichLatest(planId);

        verify(rebuilder).rebuild(documentVersionId);
        verify(publisher).publish(lesson);
    }

    private TeachingPlan plan(UUID planId, UUID documentVersionId) {
        return new TeachingPlan(planId, documentVersionId, 2, 2, 30, "测试游戏", "测试前提", List.of(), "owner", Instant.now());
    }

    private IllustratedLesson lesson(UUID planId) {
        return new IllustratedLesson(
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.DRAFT_READY, List.of(), "test", Instant.now());
    }
}
