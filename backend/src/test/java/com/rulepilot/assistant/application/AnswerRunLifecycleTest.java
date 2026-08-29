package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunDetails;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRunLifecycleTest {

    @Test
    void startsTheAnswerRunWithTheExactOwnedScope() {
        RecordingRuns runs = new RecordingRuns();
        AnswerRunLifecycle lifecycle = new AnswerRunLifecycle(runs);
        UUID subjectId = UUID.randomUUID();

        RunSnapshot started = lifecycle.start(AssistantRunMode.QUESTION_ANSWER, subjectId, "player");

        assertThat(runs.start()).isEqualTo(new Start(AssistantRunMode.QUESTION_ANSWER, subjectId, "player"));
        assertThat(started.state()).isEqualTo(AssistantRunState.RECEIVED);
    }

    @Test
    void persistsEachPolicySelectedCompletionTransitionWithTheLatestRevision() {
        RecordingRuns runs = new RecordingRuns();
        AnswerRunLifecycle lifecycle = new AnswerRunLifecycle(runs);
        RunSnapshot received = run(AssistantRunState.RECEIVED, 1);

        RunSnapshot completed = lifecycle.finish(
                received, answered(), AnswerRunProgressPolicy.ExecutionPhase.CRITIQUING);

        assertThat(runs.advances()).extracting(Advance::nextState).containsExactly(
                AssistantRunState.QUESTION_UNDERSTANDING,
                AssistantRunState.RETRIEVAL_PLANNING,
                AssistantRunState.RETRIEVING,
                AssistantRunState.VERIFYING_EVIDENCE,
                AssistantRunState.ANSWER_COMPOSITION,
                AssistantRunState.CRITIQUING,
                AssistantRunState.COMPLETED);
        assertThat(runs.advances()).extracting(Advance::expectedRevision).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(completed.state()).isEqualTo(AssistantRunState.COMPLETED);
        assertThat(completed.revision()).isEqualTo(8);
    }

    @Test
    void preservesTheOriginalWorkflowFailureWhenRunTrackingAlsoFails() {
        RecordingRuns runs = new RecordingRuns();
        runs.failWith(new IllegalStateException("run storage unavailable"));
        AnswerRunLifecycle lifecycle = new AnswerRunLifecycle(runs);
        IllegalArgumentException workflowFailure = new IllegalArgumentException("answer workflow failed");

        lifecycle.fail(
                run(AssistantRunState.RETRIEVING, 4),
                AnswerRunProgressPolicy.ExecutionPhase.RETRIEVING,
                "ANSWER_FAILED",
                "Answer workflow failed safely",
                workflowFailure);
        lifecycle.fail(
                run(AssistantRunState.COMPLETED, 8),
                AnswerRunProgressPolicy.ExecutionPhase.CRITIQUING,
                "ANSWER_FAILED",
                "Already terminal",
                workflowFailure);

        assertThat(runs.failures()).containsExactly(new Failure(
                "ANSWER_FAILED", "Answer workflow failed safely during RETRIEVING", 4));
        assertThat(workflowFailure.getSuppressed())
                .singleElement()
                .satisfies(trackingFailure -> assertThat(trackingFailure).hasMessage("run storage unavailable"));
    }

    private StructuredRuleAnswer answered() {
        UUID documentVersionId = UUID.randomUUID();
        return new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "可以执行。",
                "引用规则直接支持该结论。",
                List.of(new RuleCitation(UUID.randomUUID(), documentVersionId, "RULES", "规则", "可以执行。", 2, 2)),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                null);
    }

    private RunSnapshot run(AssistantRunState state, long revision) {
        Instant now = Instant.parse("2026-07-24T04:00:00Z");
        return new RunSnapshot(
                UUID.randomUUID(),
                AssistantRunMode.QUESTION_ANSWER,
                UUID.randomUUID(),
                "player",
                state,
                revision,
                now,
                now,
                state.terminal() ? now : null,
                null);
    }

    private static final class RecordingRuns implements AssistantRuns {

        private final List<Advance> advances = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private RuntimeException failException;
        private Start start;

        @Override
        public RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
            start = new Start(mode, subjectId, ownerUsername);
            return run(AssistantRunState.RECEIVED, 1);
        }

        @Override
        public RunSnapshot advance(UUID runId, long expectedRevision, AssistantRunState nextState, String stepSummary) {
            advances.add(new Advance(expectedRevision, nextState, stepSummary));
            Instant now = Instant.parse("2026-07-24T04:00:00Z");
            return new RunSnapshot(
                    runId,
                    AssistantRunMode.QUESTION_ANSWER,
                    UUID.randomUUID(),
                    "player",
                    nextState,
                    expectedRevision + 1,
                    now,
                    now,
                    nextState.terminal() ? now : null,
                    null);
        }

        @Override
        public RunSnapshot advanceAfterWork(UUID runId, long expectedRevision, AssistantRunState nextState, String stepSummary) {
            throw new UnsupportedOperationException("not used by answer runs");
        }

        @Override
        public RunSnapshot fail(UUID runId, long expectedRevision, String errorCode, String stepSummary) {
            failures.add(new Failure(errorCode, stepSummary, expectedRevision));
            if (failException != null) throw failException;
            return run(AssistantRunState.FAILED, expectedRevision + 1);
        }

        @Override
        public void requestCancellation(UUID runId, String ownerUsername) {
            throw new UnsupportedOperationException("not used by this lifecycle assertion");
        }

        @Override
        public void deleteOwned(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
            throw new UnsupportedOperationException("not used by this lifecycle assertion");
        }

        @Override
        public Optional<RunDetails> findOwned(UUID runId, String ownerUsername) {
            return Optional.empty();
        }

        @Override
        public Optional<RunDetails> findForAdministrativeAudit(UUID runId) {
            return Optional.empty();
        }

        @Override
        public Optional<RunDetails> findLatestOwned(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
            return Optional.empty();
        }

        @Override
        public Optional<RunDetails> findLatestOwned(
                AssistantRunMode mode,
                UUID subjectId,
                String ownerUsername,
                UUID activityRunId,
                long afterActivitySequence) {
            return Optional.empty();
        }

        @Override
        public List<RunSnapshot> findActiveOwned(AssistantRunMode mode, String ownerUsername) {
            return List.of();
        }

        @Override
        public int failInterrupted(AssistantRunMode mode) {
            return 0;
        }

        List<Advance> advances() {
            return List.copyOf(advances);
        }

        List<Failure> failures() {
            return List.copyOf(failures);
        }

        void failWith(RuntimeException failException) {
            this.failException = failException;
        }

        Start start() {
            return start;
        }

        private RunSnapshot run(AssistantRunState state, long revision) {
            Instant now = Instant.parse("2026-07-24T04:00:00Z");
            return new RunSnapshot(
                    UUID.randomUUID(),
                    AssistantRunMode.QUESTION_ANSWER,
                    UUID.randomUUID(),
                    "player",
                    state,
                    revision,
                    now,
                    now,
                    state.terminal() ? now : null,
                    null);
        }
    }

    private record Advance(long expectedRevision, AssistantRunState nextState, String summary) {}

    private record Failure(String errorCode, String summary, long expectedRevision) {}

    private record Start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {}
}
