package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetedAgentInvocationsTest {

    @Test
    void stopsRunningActivitiesWhenABoundedCallerTimesOut() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);
        UUID runId = UUID.randomUUID();

        invocations.stopRunning(
                runId, AgentExecutionControl.ActivityOutcome.FAILED, "Visual page interpretation timed out");

        assertThat(control.stoppedRunId).isEqualTo(runId);
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.FAILED);
        assertThat(control.summary).isEqualTo("Visual page interpretation timed out");
    }

    @Test
    void stopsOnlyTheNamedBoundedInvocationWhenParallelWorkContinues() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);
        UUID runId = UUID.randomUUID();

        invocations.stopRunning(
                runId,
                "inspectRulebookVisualBatch|4",
                AgentExecutionControl.ActivityOutcome.FAILED,
                "Visual page batch timed out");

        assertThat(control.stoppedRunId).isEqualTo(runId);
        assertThat(control.stoppedOperation).isEqualTo("inspectRulebookVisualBatch|4");
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.FAILED);
    }

    @Test
    void reservesAndAuditsSuccessfulInvocation() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);
        UUID runId = UUID.randomUUID();

        String result = invocations.invoke(
                runId, AgentExecutionControl.ActivityType.TOOL, "searchRuleEvidence", 7,
                "Evidence retrieved", () -> "four tokens", value -> 4);

        assertThat(result).isEqualTo("four tokens");
        assertThat(control.reservation.runId()).isEqualTo(runId);
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.SUCCEEDED);
        assertThat(control.outputTokens).isEqualTo(4);
        assertThat(control.summary).isEqualTo("Evidence retrieved");
    }

    @Test
    void derivesAContentFreeSuccessSummaryFromTheInvocationResult() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);

        String result = invocations.invoke(
                UUID.randomUUID(), AgentExecutionControl.ActivityType.MODEL, "composeTeachingSection|1", 211,
                "Teaching section composed", () -> "provider usage", value -> 19,
                value -> "Teaching section composed u=i:211,o:19,h:128");

        assertThat(result).isEqualTo("provider usage");
        assertThat(control.outputTokens).isEqualTo(19);
        assertThat(control.summary).isEqualTo("Teaching section composed u=i:211,o:19,h:128");
    }

    @Test
    void preservesCompletedWorkWhenActualUsageConsumesTheLastAvailableTokens() {
        RecordingControl control = new RecordingControl();
        control.stopOnSuccessfulComplete = StopReason.TOKEN_BUDGET;
        var invocations = new BudgetedAgentInvocations(control);

        String result = invocations.invoke(
                UUID.randomUUID(), AgentExecutionControl.ActivityType.MODEL, "composeRuleAnswer", 5,
                "Answer composed", () -> "complete cited answer", value -> 40);

        assertThat(result).isEqualTo("complete cited answer");
    }

    @Test
    void neverPublishesCompletedWorkAfterCancellationWinsTheAuditBoundary() {
        RecordingControl control = new RecordingControl();
        control.stopOnSuccessfulComplete = StopReason.CANCELLED;
        var invocations = new BudgetedAgentInvocations(control);

        assertThatThrownBy(() -> invocations.invoke(
                        UUID.randomUUID(), AgentExecutionControl.ActivityType.MODEL, "composeRuleAnswer", 5,
                        "Answer composed", () -> "must not publish", value -> 4))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .extracting("reason")
                .isEqualTo(StopReason.CANCELLED);
    }

    @Test
    void auditsFailureWithoutReplacingOriginalException() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);

        assertThatThrownBy(() -> invocations.invoke(
                        UUID.randomUUID(), AgentExecutionControl.ActivityType.MODEL, "composeRuleAnswer", 5,
                        "Answer composed", () -> {
                            throw new IllegalStateException("provider failed");
                        }, value -> 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider failed");
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.FAILED);
        assertThat(control.summary).isEqualTo("composeRuleAnswer failed safely");
    }

    @Test
    void keepsTheProviderFailurePrimaryWhenItsAuditAlsoFails() {
        RecordingControl control = new RecordingControl();
        control.failOnComplete = true;
        var invocations = new BudgetedAgentInvocations(control);

        assertThatThrownBy(() -> invocations.invoke(
                        UUID.randomUUID(), AgentExecutionControl.ActivityType.MODEL, "composeRuleAnswer", 5,
                        "Answer composed", () -> {
                            throw new IllegalStateException("provider failed");
                        }, value -> 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("audit failed"));
    }

    @Test
    void budgetRejectionStopsBeforeInvocation() {
        RecordingControl control = new RecordingControl();
        control.stopOnReserve = true;
        var invocations = new BudgetedAgentInvocations(control);

        assertThatThrownBy(() -> invocations.invoke(
                        UUID.randomUUID(), AgentExecutionControl.ActivityType.TOOL, "searchRuleEvidence", 2,
                        "Evidence retrieved", () -> "must not run", value -> 1))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .extracting("reason")
                .isEqualTo(StopReason.TOOL_BUDGET);
        assertThat(control.outcome).isNull();
    }

    @Test
    void recordsValidationDiagnosticWithoutReservingInvocationBudget() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);
        UUID runId = UUID.randomUUID();

        invocations.record(
                runId,
                AgentExecutionControl.ActivityType.VALIDATION,
                "validateTeachingSection|3|1",
                AgentExecutionControl.ActivityOutcome.REJECTED,
                "Teaching draft rejected: STEP_COUNT_INVALID");

        assertThat(control.reservation).isNull();
        assertThat(control.recordedRunId).isEqualTo(runId);
        assertThat(control.recordedType).isEqualTo(AgentExecutionControl.ActivityType.VALIDATION);
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.REJECTED);
        assertThat(control.summary).isEqualTo("Teaching draft rejected: STEP_COUNT_INVALID");
    }

    @Test
    void allows_a_running_validation_activity_to_be_settled_as_a_player_visible_success() {
        RecordingControl control = new RecordingControl();
        var invocations = new BudgetedAgentInvocations(control);
        UUID runId = UUID.randomUUID();

        invocations.record(
                runId,
                AgentExecutionControl.ActivityType.VALIDATION,
                "visualStep|2|3",
                AgentExecutionControl.ActivityOutcome.RUNNING,
                "正在查看“选择行动”中的“拿两张牌”规则图示");
        invocations.stopRunning(
                runId,
                "visualStep|2|3",
                AgentExecutionControl.ActivityOutcome.SUCCEEDED,
                "“选择行动”中的“拿两张牌”：已找到可核对的局部图示");

        assertThat(control.stoppedOperation).isEqualTo("visualStep|2|3");
        assertThat(control.outcome).isEqualTo(AgentExecutionControl.ActivityOutcome.SUCCEEDED);
    }

    private static final class RecordingControl implements AgentExecutionControl {
        private InvocationReservation reservation;
        private ActivityOutcome outcome;
        private int outputTokens;
        private String summary;
        private boolean stopOnReserve;
        private boolean failOnComplete;
        private StopReason stopOnSuccessfulComplete;
        private UUID recordedRunId;
        private UUID stoppedRunId;
        private String stoppedOperation;
        private ActivityType recordedType;

        @Override
        public void initialize(UUID runId, BudgetLimits limits, Instant startedAt) {}

        @Override
        public void assertStepAllowed(UUID runId, long nextStep) {}

        @Override
        public InvocationReservation reserve(
                UUID runId, ActivityType type, String operation, int estimatedInputTokens) {
            if (stopOnReserve) throw new AgentExecutionStoppedException(StopReason.TOOL_BUDGET);
            reservation = new InvocationReservation(UUID.randomUUID(), runId, type, operation, estimatedInputTokens);
            return reservation;
        }

        @Override
        public void complete(
                InvocationReservation reservation,
                ActivityOutcome outcome,
                int estimatedOutputTokens,
                long latencyMs,
                String summary) {
            if (failOnComplete) throw new IllegalStateException("audit failed");
            if (outcome == ActivityOutcome.SUCCEEDED && stopOnSuccessfulComplete != null) {
                throw new AgentExecutionStoppedException(stopOnSuccessfulComplete);
            }
            this.outcome = outcome;
            this.outputTokens = estimatedOutputTokens;
            this.summary = summary;
        }

        @Override
        public void record(
                UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            this.recordedRunId = runId;
            this.recordedType = type;
            this.outcome = outcome;
            this.summary = summary;
        }

        @Override
        public void stopRunning(UUID runId, ActivityOutcome outcome, String summary) {
            this.stoppedRunId = runId;
            this.outcome = outcome;
            this.summary = summary;
        }

        @Override
        public void stopRunning(UUID runId, String operation, ActivityOutcome outcome, String summary) {
            this.stoppedRunId = runId;
            this.stoppedOperation = operation;
            this.outcome = outcome;
            this.summary = summary;
        }

        @Override
        public void requestCancellation(UUID runId, String ownerUsername) {}

        @Override
        public BudgetSnapshot budget(UUID runId) {
            return null;
        }

        @Override
        public List<ActivitySnapshot> activities(UUID runId) {
            return List.of();
        }
    }
}
