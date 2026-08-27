package com.rulepilot.teaching.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
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
    void skipsEveryVisualModelCallWhenTheOutlineRequestsNoVisualEvidence() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var verifying = run(runId, planId, AssistantRunState.VERIFYING_EVIDENCE, 4, null, null);
        var packaging = run(runId, planId, AssistantRunState.MEDIA_PACKAGING, 5, null, null);
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId, false)));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.advance(runId, 3, AssistantRunState.VERIFYING_EVIDENCE,
                "讲解大纲没有需要图片才能说明的章节，跳过额外视觉模型调用")).thenReturn(verifying);
        when(runs.advance(runId, 4, AssistantRunState.MEDIA_PACKAGING, "保留已经发布的文字讲解"))
                .thenReturn(packaging);

        new VisualLessonEnrichmentService(
                plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        Mockito.verifyNoInteractions(lessons, enricher, publisher, rebuilder, activities);
        verify(runs).advance(runId, 5, AssistantRunState.COMPLETED, "视觉检查无需执行");
    }

    @Test
    void rebuilds_missing_layout_evidence_once_before_retrying_visual_enrichment() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var verifying = run(runId, planId, AssistantRunState.VERIFYING_EVIDENCE, 4, null, null);
        var packaging = run(runId, planId, AssistantRunState.MEDIA_PACKAGING, 5, null, null);
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.findOwned(runId, "owner")).thenReturn(Optional.of(new AssistantRuns.RunDetails(
                retrieving, List.of(), null, List.of())));
        when(runs.advance(runId, 3, AssistantRunState.VERIFYING_EVIDENCE,
                "Checking that every selected crop has cited rule evidence")).thenReturn(verifying);
        when(runs.advance(runId, 4, AssistantRunState.MEDIA_PACKAGING,
                "Publishing accepted local rulebook crops")).thenReturn(packaging);
        when(enricher.enrichWithProgress(
                        Mockito.eq(documentVersionId),
                        Mockito.eq(lesson),
                        Mockito.eq("owner"),
                        Mockito.eq(runId),
                        Mockito.any()))
                .thenThrow(new IllegalArgumentException("rulebook understanding does not exist"))
                .thenReturn(new VisualLessonEnricher.EnrichmentResult(lesson, List.of()));

        new VisualLessonEnrichmentService(
                plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        verify(rebuilder).rebuild(documentVersionId);
        verify(publisher).publish(lesson);
        verify(runs).advance(runId, 5, AssistantRunState.COMPLETED, "Visual enrichment finished");
    }

    @Test
    void stops_between_visual_steps_when_the_player_cancels_the_run() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var cancelled = run(runId, planId, AssistantRunState.FAILED, 4, Instant.now(), "AGENT_CANCELLED");
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.findOwned(runId, "owner")).thenReturn(Optional.of(new AssistantRuns.RunDetails(
                cancelled, List.of(), null, List.of())));
        Mockito.doAnswer(invocation -> {
                    VisualLessonEnricher.VisualProgressListener progress = invocation.getArgument(4);
                    progress.targetStarted(new VisualLessonEnricher.VisualTarget(1, "开局设置", 2, "摆放组件"));
                    return new VisualLessonEnricher.EnrichmentResult(lesson, List.of());
                })
                .when(enricher)
                .enrichWithProgress(
                        Mockito.eq(documentVersionId), Mockito.eq(lesson), Mockito.eq("owner"),
                        Mockito.eq(runId), Mockito.any());

        new VisualLessonEnrichmentService(plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        Mockito.verifyNoInteractions(publisher, activities);
        Mockito.verify(runs, Mockito.never())
                .fail(Mockito.eq(runId), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void publishes_a_verified_visual_section_before_the_background_run_finishes() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var verifying = run(runId, planId, AssistantRunState.VERIFYING_EVIDENCE, 4, null, null);
        var packaging = run(runId, planId, AssistantRunState.MEDIA_PACKAGING, 5, null, null);
        var completed = run(runId, planId, AssistantRunState.COMPLETED, 6, Instant.now(), null);
        var update = new VisualLessonEnricher.SectionProgress(
                1,
                "开局设置",
                new VisualLessonEnricher.SectionOutcome(1, VisualLessonEnricher.Outcome.ADDED, "第 1 节已加入可核对的局部规则书截图"));

        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.advance(runId, 3, AssistantRunState.VERIFYING_EVIDENCE,
                "Checking that every selected crop has cited rule evidence")).thenReturn(verifying);
        when(runs.advance(runId, 4, AssistantRunState.MEDIA_PACKAGING,
                "Publishing accepted local rulebook crops")).thenReturn(packaging);
        when(runs.advance(runId, 5, AssistantRunState.COMPLETED, "Visual enrichment finished")).thenReturn(completed);
        when(runs.findOwned(runId, "owner")).thenReturn(Optional.of(new AssistantRuns.RunDetails(
                retrieving, List.of(), null, List.of())));
        Mockito.doAnswer(invocation -> {
                    VisualLessonEnricher.VisualProgressListener progress = invocation.getArgument(4);
                    progress.sectionFinished(update);
                    progress.sectionUpdated(update, lesson);
                    return new VisualLessonEnricher.EnrichmentResult(lesson, List.of(update.outcome()));
                })
                .when(enricher)
                .enrichWithProgress(
                        Mockito.eq(documentVersionId), Mockito.eq(lesson), Mockito.eq("owner"),
                        Mockito.eq(runId), Mockito.any());

        new VisualLessonEnrichmentService(plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        var order = Mockito.inOrder(publisher, runs);
        order.verify(publisher).publish(lesson);
        order.verify(runs).advance(runId, 3, AssistantRunState.VERIFYING_EVIDENCE,
                "Checking that every selected crop has cited rule evidence");
        Mockito.verify(publisher, Mockito.times(2)).publish(lesson);
    }

    @Test
    void records_the_specific_reason_when_a_crop_does_not_match_the_current_step() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var verifying = run(runId, planId, AssistantRunState.VERIFYING_EVIDENCE, 4, null, null);
        var packaging = run(runId, planId, AssistantRunState.MEDIA_PACKAGING, 5, null, null);
        var completed = run(runId, planId, AssistantRunState.COMPLETED, 6, Instant.now(), null);
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.advance(runId, 3, AssistantRunState.VERIFYING_EVIDENCE,
                "Checking that every selected crop has cited rule evidence")).thenReturn(verifying);
        when(runs.advance(runId, 4, AssistantRunState.MEDIA_PACKAGING,
                "Publishing accepted local rulebook crops")).thenReturn(packaging);
        when(runs.advance(runId, 5, AssistantRunState.COMPLETED, "Visual enrichment finished")).thenReturn(completed);
        when(runs.findOwned(runId, "owner")).thenReturn(Optional.of(new AssistantRuns.RunDetails(
                retrieving, List.of(), null, List.of())));
        Mockito.doAnswer(invocation -> {
                    VisualLessonEnricher.VisualProgressListener progress = invocation.getArgument(4);
                    var target = new VisualLessonEnricher.VisualTarget(1, "开局设置", 2, "摆放组件");
                    progress.targetStarted(target);
                    progress.targetFinished(target, VisualLessonEnricher.Outcome.REJECTED_STEP_MISMATCH);
                    return new VisualLessonEnricher.EnrichmentResult(lesson, List.of());
                })
                .when(enricher)
                .enrichWithProgress(
                        Mockito.eq(documentVersionId), Mockito.eq(lesson), Mockito.eq("owner"),
                        Mockito.eq(runId), Mockito.any());

        new VisualLessonEnrichmentService(plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        Mockito.verify(activities).stopRunning(
                Mockito.eq(runId),
                Mockito.eq("visualStep|1|2"),
                Mockito.eq(com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome.REJECTED),
                Mockito.contains("与当前规则步骤不一致"));
    }

    @Test
    void does_not_publish_or_advance_when_the_player_cancels_during_the_last_visual_check() {
        TeachingPlanRepository plans = Mockito.mock(TeachingPlanRepository.class);
        IllustratedLessonRepository lessons = Mockito.mock(IllustratedLessonRepository.class);
        VisualLessonEnricher enricher = Mockito.mock(VisualLessonEnricher.class);
        IllustratedLessonProgressPublisher publisher = Mockito.mock(IllustratedLessonProgressPublisher.class);
        RulebookUnderstandingRebuilder rebuilder = Mockito.mock(RulebookUnderstandingRebuilder.class);
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        AuditedAgentInvocations activities = Mockito.mock(AuditedAgentInvocations.class);
        UUID planId = UUID.randomUUID();
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IllustratedLesson lesson = lesson(planId);
        var received = run(runId, planId, AssistantRunState.RECEIVED, 1, null, null);
        var ready = run(runId, planId, AssistantRunState.DOCUMENT_READINESS, 2, null, null);
        var retrieving = run(runId, planId, AssistantRunState.RETRIEVING, 3, null, null);
        var cancelled = run(runId, planId, AssistantRunState.FAILED, 4, Instant.now(), "AGENT_CANCELLED");
        when(plans.findById(planId)).thenReturn(Optional.of(plan(planId, documentVersionId)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(lesson));
        when(runs.advance(runId, 1, AssistantRunState.DOCUMENT_READINESS, "Loading cited pages and visual candidates"))
                .thenReturn(ready);
        when(runs.advance(runId, 2, AssistantRunState.RETRIEVING, "Looking for compact, player-useful rulebook regions"))
                .thenReturn(retrieving);
        when(runs.findOwned(runId, "owner")).thenReturn(Optional.of(new AssistantRuns.RunDetails(
                cancelled, List.of(), null, List.of())));
        when(enricher.enrichWithProgress(
                        Mockito.eq(documentVersionId), Mockito.eq(lesson), Mockito.eq("owner"),
                        Mockito.eq(runId), Mockito.any()))
                .thenReturn(new VisualLessonEnricher.EnrichmentResult(lesson, List.of()));

        new VisualLessonEnrichmentService(plans, lessons, enricher, publisher, rebuilder, runs, activities)
                .enrichLatest(planId, received);

        Mockito.verifyNoInteractions(publisher, activities);
        Mockito.verify(runs, Mockito.never())
                .advance(Mockito.eq(runId), Mockito.anyLong(), Mockito.eq(AssistantRunState.VERIFYING_EVIDENCE), Mockito.anyString());
        Mockito.verify(runs, Mockito.never())
                .fail(Mockito.eq(runId), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    }

    private TeachingPlan plan(UUID planId, UUID documentVersionId) {
        return plan(planId, documentVersionId, true);
    }

    private TeachingPlan plan(UUID planId, UUID documentVersionId, boolean visualEvidenceRecommended) {
        var section = new TeachingPlan.PlannedSection(
                1,
                "setup",
                "开局设置",
                "讲清楚开始游戏前的准备。",
                true,
                visualEvidenceRecommended,
                List.of("SETUP"),
                List.of("setup"),
                List.of(1));
        return new TeachingPlan(
                planId,
                documentVersionId,
                "测试游戏",
                "测试前提",
                List.of(section),
                "owner",
                Instant.now());
    }

    private IllustratedLesson lesson(UUID planId) {
        return new IllustratedLesson(
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.DRAFT_READY, List.of(), "test", Instant.now());
    }

    private AssistantRuns.RunSnapshot run(
            UUID runId,
            UUID planId,
            AssistantRunState state,
            long revision,
            Instant completedAt,
            String lastErrorCode) {
        Instant now = Instant.now();
        return new AssistantRuns.RunSnapshot(
                runId,
                AssistantRunMode.VISUAL_ENRICHMENT,
                planId,
                "owner",
                state,
                revision,
                now,
                now,
                completedAt,
                lastErrorCode);
    }
}
