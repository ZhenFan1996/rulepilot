package com.rulepilot.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistantRunTest {

    @Test
    void teachingPreparationCompletesAfterLessonPlanning() {
        AssistantRun run = AssistantRun.start(
                        AssistantRunMode.TEACHING_PREPARATION, UUID.randomUUID(), "teacher", STARTED)
                .advance(AssistantRunState.DOCUMENT_READINESS, STARTED.plusSeconds(1))
                .advance(AssistantRunState.LESSON_PLANNING, STARTED.plusSeconds(2))
                .advance(AssistantRunState.COMPLETED, STARTED.plusSeconds(3));

        assertThat(run.state()).isEqualTo(AssistantRunState.COMPLETED);
        assertThat(run.completedAt()).isEqualTo(STARTED.plusSeconds(3));
    }

    @Test
    void visualEnrichmentTracksCandidateSelectionBeforePublishingCrops() {
        AssistantRun run = AssistantRun.start(AssistantRunMode.VISUAL_ENRICHMENT, UUID.randomUUID(), "teacher", STARTED);

        run = advance(run, AssistantRunState.DOCUMENT_READINESS, 1);
        run = advance(run, AssistantRunState.RETRIEVING, 2);
        run = advance(run, AssistantRunState.VERIFYING_EVIDENCE, 3);
        run = advance(run, AssistantRunState.MEDIA_PACKAGING, 4);
        run = advance(run, AssistantRunState.COMPLETED, 5);

        assertThat(run.state()).isEqualTo(AssistantRunState.COMPLETED);
        assertThat(run.completedAt()).isEqualTo(STARTED.plusSeconds(5));
    }

    private static final Instant STARTED = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void advancesATeachingRunThroughGroundedLessonComposition() {
        AssistantRun run = AssistantRun.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "teacher", STARTED);

        run = advance(run, AssistantRunState.DOCUMENT_READINESS, 1);
        run = advance(run, AssistantRunState.LESSON_PLANNING, 2);
        run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, 3);
        run = advance(run, AssistantRunState.RETRIEVING, 4);
        run = advance(run, AssistantRunState.VERIFYING_EVIDENCE, 5);
        run = advance(run, AssistantRunState.LESSON_COMPOSITION, 6);
        run = advance(run, AssistantRunState.MEDIA_PACKAGING, 7);
        run = advance(run, AssistantRunState.COMPLETED, 8);

        assertThat(run.state()).isEqualTo(AssistantRunState.COMPLETED);
        assertThat(run.revision()).isEqualTo(9);
        assertThat(run.completedAt()).isEqualTo(STARTED.plusSeconds(8));
    }

    @Test
    void questionClarificationSettlesTheNativeAgentRun() {
        AssistantRun run = AssistantRun.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", STARTED);

        run = advance(run, AssistantRunState.ANSWER_COMPOSITION, 1);
        run = advance(run, AssistantRunState.NEED_CLARIFICATION, 2);

        assertThat(run.state()).isEqualTo(AssistantRunState.NEED_CLARIFICATION);
        assertThat(run.completedAt()).isEqualTo(STARTED.plusSeconds(2));
        AssistantRun terminal = run;
        assertThatThrownBy(() -> terminal.advance(
                        AssistantRunState.ANSWER_COMPOSITION, STARTED.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void questionRunCanTerminateFromThePhaseThatActuallyProducedItsOutcome() {
        AssistantRun chat = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", STARTED)
                .advance(AssistantRunState.ANSWER_COMPOSITION, STARTED.plusSeconds(1))
                .advance(AssistantRunState.COMPLETED, STARTED.plusSeconds(2));
        AssistantRun clarification = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", STARTED)
                .advance(AssistantRunState.ANSWER_COMPOSITION, STARTED.plusSeconds(1))
                .advance(AssistantRunState.NEED_CLARIFICATION, STARTED.plusSeconds(2));
        AssistantRun boundaryFailure = AssistantRun.start(
                        AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", STARTED)
                .advance(AssistantRunState.ANSWER_COMPOSITION, STARTED.plusSeconds(1))
                .advance(AssistantRunState.DEGRADED, STARTED.plusSeconds(2));

        assertThat(chat.state()).isEqualTo(AssistantRunState.COMPLETED);
        assertThat(clarification.state()).isEqualTo(AssistantRunState.NEED_CLARIFICATION);
        assertThat(boundaryFailure.state()).isEqualTo(AssistantRunState.DEGRADED);
    }

    @Test
    void rejectsCrossWorkflowAndTerminalTransitions() {
        AssistantRun run = AssistantRun.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "teacher", STARTED);

        assertThatThrownBy(() -> run.advance(AssistantRunState.QUESTION_UNDERSTANDING, STARTED.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        AssistantRun failed = run.fail("MODEL_TIMEOUT", STARTED.plusSeconds(1));
        assertThat(failed.lastErrorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThatThrownBy(() -> failed.advance(AssistantRunState.DOCUMENT_READINESS, STARTED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private AssistantRun advance(AssistantRun run, AssistantRunState state, long seconds) {
        return run.advance(state, STARTED.plusSeconds(seconds));
    }
}
