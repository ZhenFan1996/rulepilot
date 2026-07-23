package com.rulepilot.teaching.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationOutcome;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IllustratedLessonServiceTest {

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
}
