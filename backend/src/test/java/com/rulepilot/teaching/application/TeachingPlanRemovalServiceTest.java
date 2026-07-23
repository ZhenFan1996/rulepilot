package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanRemovalServiceTest {

    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final IllustratedLessonRepository lessons = mock(IllustratedLessonRepository.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final TeachingPlanRemovalService service = new TeachingPlanRemovalService(plans, lessons, runs);

    @Test
    void removesOnlyAnOwnedPlan() {
        TeachingPlan plan = plan("alice", 4, 2, 30, Instant.parse("2026-07-23T01:00:00Z"));
        when(plans.findByIdAndCreatedBy(plan.id(), "alice")).thenReturn(Optional.of(plan));

        service.removeOwned(plan.id(), "alice");

        verify(plans).delete(plan.id());
        verify(runs).deleteOwned(com.rulepilot.assistant.AssistantRunMode.TEACHING, plan.id(), "alice");
        verify(runs).deleteOwned(com.rulepilot.assistant.AssistantRunMode.VISUAL_ENRICHMENT, plan.id(), "alice");
    }

    @Test
    void duplicateCleanupKeepsTheMostCompletePlanBeforeUsingRecency() {
        UUID versionId = UUID.randomUUID();
        TeachingPlan olderComplete = plan(versionId, "alice", 4, 2, 30, Instant.parse("2026-07-20T01:00:00Z"));
        TeachingPlan newerUnstarted = plan(versionId, "alice", 4, 2, 30, Instant.parse("2026-07-23T01:00:00Z"));
        when(plans.findAllByCreatedBy("alice")).thenReturn(List.of(olderComplete, newerUnstarted));
        when(lessons.findLatestByPlan(olderComplete.id())).thenReturn(Optional.of(lesson(olderComplete, IllustratedLesson.LessonStatus.COMPLETE)));
        when(lessons.findLatestByPlan(newerUnstarted.id())).thenReturn(Optional.empty());

        var result = service.removeDuplicatePlans("alice");

        assertThat(result.deletedCount()).isEqualTo(1);
        verify(plans).delete(newerUnstarted.id());
    }

    private TeachingPlan plan(String owner, int players, int beginners, int minutes, Instant createdAt) {
        return plan(UUID.randomUUID(), owner, players, beginners, minutes, createdAt);
    }

    private TeachingPlan plan(UUID versionId, String owner, int players, int beginners, int minutes, Instant createdAt) {
        return new TeachingPlan(
                UUID.randomUUID(), versionId, players, beginners, minutes, "Game", "Premise", List.of(), owner, createdAt);
    }

    private IllustratedLesson lesson(TeachingPlan plan, IllustratedLesson.LessonStatus status) {
        return new IllustratedLesson(UUID.randomUUID(), plan.id(), status, List.of(), "test", Instant.now());
    }
}
