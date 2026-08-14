package com.rulepilot.teaching.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationOutcome;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IllustratedLessonServiceTest {

    @Test
    void isolatesCandidateRunDiscoveryFromThePlayerVisiblePlanSubject() {
        UUID planId = UUID.randomUUID();

        UUID first = IllustratedLessonService.candidateSubjectId(planId);

        assertThat(first).isEqualTo(IllustratedLessonService.candidateSubjectId(planId));
        assertThat(first).isNotEqualTo(planId);
        assertThat(first).isNotEqualTo(IllustratedLessonService.candidateSubjectId(UUID.randomUUID()));
    }

    @Test
    void finalizesAPersistedLessonWithoutReopeningItsExecutionBudget() {
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot verified = run(AssistantRunState.VERIFYING_EVIDENCE, 5);
        RunSnapshot composed = run(verified.id(), AssistantRunState.LESSON_COMPOSITION, 6);
        RunSnapshot completed = run(verified.id(), AssistantRunState.COMPLETED, 7);
        when(runs.advanceAfterWork(
                        verified.id(), 5, AssistantRunState.LESSON_COMPOSITION,
                        "Cited illustrated lesson is composed"))
                .thenReturn(composed);
        when(runs.advanceAfterWork(
                        verified.id(), 6, AssistantRunState.COMPLETED,
                        "Illustrated lesson generation completed"))
                .thenReturn(completed);
        IllustratedLessonService service = service(runs);

        service.finish(new GenerationOutcome(verified, LessonStatus.DRAFT_READY));

        verify(runs).advanceAfterWork(
                verified.id(), 5, AssistantRunState.LESSON_COMPOSITION, "Cited illustrated lesson is composed");
        verify(runs).advanceAfterWork(
                verified.id(), 6, AssistantRunState.COMPLETED, "Illustrated lesson generation completed");
        verify(runs, never()).advance(any(), any(Long.class), any(), any());
    }

    @Test
    void startsFromAnAlreadyHydratedOwnedPlanWithoutReadingThePlanRepositoryAgain() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(plan.documentVersionId()).thenReturn(versionId);
        when(plan.createdBy()).thenReturn("alice");
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        AssistantRuns runs = mock(AssistantRuns.class);
        DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
        when(documents.findVersion(versionId))
                .thenReturn(java.util.Optional.of(new VersionScope(versionId, UUID.randomUUID(), "READY", "alice")));
        RunSnapshot received = run(planId, "alice", AssistantRunState.RECEIVED, 1);
        when(runs.start(AssistantRunMode.TEACHING, planId, "alice")).thenReturn(received);
        IllustratedLessonService service = new IllustratedLessonService(
                plans,
                mock(GroundedTeachingAgent.class),
                mock(IllustratedLessonRepository.class),
                runs,
                documents,
                ObservationRegistry.NOOP,
                mock(IllustratedLessonProgressPublisher.class));

        RunSnapshot started = service.begin(plan, "alice");

        assertThat(started).isEqualTo(received);
        verify(documents).findVersion(versionId);
        verify(plans, never()).findById(planId);
    }

    @Test
    void rejectsAnAlreadyHydratedPlanWhenThePreparedRunHasAnotherSubject() {
        UUID planId = UUID.randomUUID();
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(plan.createdBy()).thenReturn("alice");
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        IllustratedLessonService service = new IllustratedLessonService(
                mock(TeachingPlanRepository.class),
                agent,
                mock(IllustratedLessonRepository.class),
                mock(AssistantRuns.class),
                mock(DocumentVersionScopeLookup.class),
                ObservationRegistry.NOOP,
                mock(IllustratedLessonProgressPublisher.class));
        RunSnapshot wrongRun = run(UUID.randomUUID(), "alice", AssistantRunState.RECEIVED, 1);

        assertThatThrownBy(() -> service.startGeneration(plan, "alice", wrongRun))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prepared teaching run does not match the plan");

        verifyNoInteractions(agent);
    }

    private IllustratedLessonService service(AssistantRuns runs) {
        return new IllustratedLessonService(
                mock(TeachingPlanRepository.class),
                mock(GroundedTeachingAgent.class),
                mock(IllustratedLessonRepository.class),
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(ObservationRegistry.class),
                mock(IllustratedLessonProgressPublisher.class));
    }

    private RunSnapshot run(AssistantRunState state, long revision) {
        return run(UUID.randomUUID(), state, revision);
    }

    private RunSnapshot run(UUID id, AssistantRunState state, long revision) {
        Instant now = Instant.parse("2026-07-23T09:00:00Z");
        return new RunSnapshot(
                id, AssistantRunMode.TEACHING, UUID.randomUUID(), "player", state, revision, now, now,
                state.terminal() ? now : null, null);
    }

    private RunSnapshot run(
            UUID subjectId,
            String owner,
            AssistantRunState state,
            long revision) {
        Instant now = Instant.parse("2026-07-23T09:00:00Z");
        return new RunSnapshot(
                UUID.randomUUID(), AssistantRunMode.TEACHING, subjectId, owner, state, revision, now, now,
                state.terminal() ? now : null, null);
    }
}
