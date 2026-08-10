package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.RulebookIconGlossaryService.GlossaryStatus;
import com.rulepilot.teaching.application.RulebookIconGlossaryService.GlossaryView;
import com.rulepilot.teaching.application.VisualLessonEnrichmentService.VisualEnrichmentLaunch;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicIconGlossaryBackfillServiceTest {

    private final PublicLessonReader publicLessons = mock(PublicLessonReader.class);
    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final RulebookIconGlossaryService glossaries = mock(RulebookIconGlossaryService.class);
    private final IllustratedLessonLauncher launcher = mock(IllustratedLessonLauncher.class);
    private final PublicIconGlossaryBackfillService service =
            new PublicIconGlossaryBackfillService(publicLessons, plans, glossaries, launcher);

    @Test
    void rejects_a_plan_that_is_not_currently_public() {
        UUID planId = UUID.randomUUID();
        when(publicLessons.find(planId)).thenReturn(Optional.empty());

        assertThat(service.launch(planId)).isEmpty();

        verify(plans, never()).findById(planId);
        verify(launcher, never()).prepareIconGlossary(planId, "owner");
    }

    @Test
    void starts_inventory_under_the_persisted_plan_owner() {
        UUID planId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TeachingPlan plan = plan(planId, "lesson-owner");
        when(publicLessons.find(planId)).thenReturn(Optional.of(mock(PublicLessonReader.PublicLesson.class)));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(glossaries.viewPublic(planId)).thenReturn(view(GlossaryStatus.NOT_STARTED));
        when(launcher.prepareIconGlossary(planId, "lesson-owner"))
                .thenReturn(new VisualEnrichmentLaunch(runId, AssistantRunState.RECEIVED, 1, false));

        var result = service.launch(planId).orElseThrow();

        assertThat(result.status()).isEqualTo(GlossaryStatus.GENERATING);
        assertThat(result.started()).isTrue();
        assertThat(result.assistantRunId()).isEqualTo(runId);
        assertThat(result.reused()).isFalse();
        verify(launcher).prepareIconGlossary(planId, "lesson-owner");
    }

    @Test
    void reuses_an_active_inventory_instead_of_scheduling_a_duplicate() {
        UUID planId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TeachingPlan plan = plan(planId, "lesson-owner");
        when(publicLessons.find(planId)).thenReturn(Optional.of(mock(PublicLessonReader.PublicLesson.class)));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(glossaries.viewPublic(planId)).thenReturn(view(GlossaryStatus.GENERATING));
        when(launcher.prepareIconGlossary(planId, "lesson-owner"))
                .thenReturn(new VisualEnrichmentLaunch(runId, AssistantRunState.RETRIEVING, 3, true));

        var result = service.launch(planId).orElseThrow();

        assertThat(result.status()).isEqualTo(GlossaryStatus.GENERATING);
        assertThat(result.started()).isFalse();
        assertThat(result.reused()).isTrue();
        assertThat(result.runState()).isEqualTo(AssistantRunState.RETRIEVING);
    }

    @Test
    void leaves_a_ready_inventory_untouched() {
        UUID planId = UUID.randomUUID();
        TeachingPlan plan = plan(planId, "lesson-owner");
        when(publicLessons.find(planId)).thenReturn(Optional.of(mock(PublicLessonReader.PublicLesson.class)));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(glossaries.viewPublic(planId)).thenReturn(view(GlossaryStatus.READY));

        var result = service.launch(planId).orElseThrow();

        assertThat(result.status()).isEqualTo(GlossaryStatus.READY);
        assertThat(result.started()).isFalse();
        assertThat(result.assistantRunId()).isNull();
        verify(launcher, never()).prepareIconGlossary(planId, "lesson-owner");
    }

    @Test
    void reports_an_unavailable_model_without_creating_a_failed_run() {
        UUID planId = UUID.randomUUID();
        TeachingPlan plan = plan(planId, "lesson-owner");
        when(publicLessons.find(planId)).thenReturn(Optional.of(mock(PublicLessonReader.PublicLesson.class)));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(glossaries.viewPublic(planId)).thenReturn(view(GlossaryStatus.UNAVAILABLE));

        var result = service.launch(planId).orElseThrow();

        assertThat(result.status()).isEqualTo(GlossaryStatus.UNAVAILABLE);
        assertThat(result.accepted()).isFalse();
        verify(launcher, never()).prepareIconGlossary(planId, "lesson-owner");
    }

    private static TeachingPlan plan(UUID planId, String owner) {
        return new TeachingPlan(
                planId,
                UUID.randomUUID(),
                "Orbit",
                "Learn the rules",
                List.of(),
                owner,
                java.time.Instant.parse("2026-07-31T00:00:00Z"));
    }

    private static GlossaryView view(GlossaryStatus status) {
        return new GlossaryView(status, 8, 0, 0, List.of(), List.of());
    }
}
