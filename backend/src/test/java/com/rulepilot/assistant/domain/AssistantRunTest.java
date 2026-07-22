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
    void questionRunCanResumeAfterClarificationAndASecondRetrievalPlan() {
        AssistantRun run = AssistantRun.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player", STARTED);

        run = advance(run, AssistantRunState.QUESTION_UNDERSTANDING, 1);
        run = advance(run, AssistantRunState.NEED_CLARIFICATION, 2);
        run = advance(run, AssistantRunState.QUESTION_UNDERSTANDING, 3);
        run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, 4);
        run = advance(run, AssistantRunState.RETRIEVING, 5);
        run = advance(run, AssistantRunState.VERIFYING_EVIDENCE, 6);
        run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, 7);

        assertThat(run.state()).isEqualTo(AssistantRunState.RETRIEVAL_PLANNING);
        assertThat(run.completedAt()).isNull();
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
