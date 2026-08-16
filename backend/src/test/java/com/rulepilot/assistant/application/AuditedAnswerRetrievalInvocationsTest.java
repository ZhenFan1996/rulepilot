package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AuditedAgentInvocations;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class AuditedAnswerRetrievalInvocationsTest {

    @Test
    void preservesTheOwningAgentToolLedgerAndStopSignal() {
        RecordingInvocations delegate = new RecordingInvocations();
        AuditedAnswerRetrievalInvocations retrievalInvocations = new AuditedAnswerRetrievalInvocations(delegate);

        String result = retrievalInvocations.invoke(
                UUID.randomUUID(),
                "hybridRuleSearch",
                17,
                "Version-scoped answer evidence retrieved",
                () -> "evidence",
                String::length);

        assertThat(result).isEqualTo("evidence");
        assertThat(delegate.type).isEqualTo(ActivityType.TOOL);
        assertThat(delegate.operation).isEqualTo("hybridRuleSearch");
        assertThat(delegate.estimatedInputTokens).isEqualTo(17);
        assertThat(delegate.outputTokens).isEqualTo(8);
        assertThat(retrievalInvocations.executionStopped(
                        new AgentExecutionStoppedException(StopReason.TOOL_BUDGET)))
                .isTrue();
        assertThat(retrievalInvocations.executionStopped(new IllegalStateException("adapter unavailable")))
                .isFalse();
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private ActivityType type;
        private String operation;
        private int estimatedInputTokens;
        private int outputTokens;

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            this.type = type;
            this.operation = operation;
            this.estimatedInputTokens = estimatedInputTokens;
            T result = invocation.get();
            this.outputTokens = outputTokenEstimator.applyAsInt(result);
            return result;
        }
    }
}
