package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRunProgressPolicyTest {

    private final UUID versionId = UUID.randomUUID();

    @Test
    void recordsOneAgentPhaseThenTheValidatedTerminalOutcome() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.ANSWERED),
                        AnswerRunProgressPolicy.ExecutionPhase.AGENT_RUNNING)))
                .containsExactly(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.COMPLETED)
                .doesNotContain(
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.CRITIQUING);
    }

    @Test
    void keepsClarificationAndTypedAgentFailureAsTheirActualTerminalBoundaries() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.CLARIFICATION_REQUIRED),
                        AnswerRunProgressPolicy.ExecutionPhase.AGENT_RUNNING)))
                .containsExactly(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.NEED_CLARIFICATION);
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.INVALID_MODEL_OUTPUT),
                        AnswerRunProgressPolicy.ExecutionPhase.AGENT_RUNNING)))
                .containsExactly(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.DEGRADED);
    }

    private List<AssistantRunState> states(List<AnswerRunProgressPolicy.ProgressUpdate> updates) {
        return updates.stream().map(AnswerRunProgressPolicy.ProgressUpdate::state).toList();
    }

    private StructuredRuleAnswer answer(AnswerStatus status) {
        List<RuleCitation> citations = status.publishesConclusion()
                ? List.of(new RuleCitation(
                        UUID.randomUUID(), versionId, "RULES", "Rule", "Follow the rule.", 1, 1))
                : List.of();
        return new StructuredRuleAnswer(
                versionId,
                status,
                "Result.",
                "The Agent supplied this complete player-facing explanation.",
                citations,
                List.of(),
                status.publishesConclusion() ? AnswerConfidence.HIGH : AnswerConfidence.LOW,
                status.publishesConclusion() ? AnswerBasis.DIRECT_RULE : null,
                false,
                null,
                null,
                status == AnswerStatus.CLARIFICATION_REQUIRED ? "Which timing do you mean?" : null);
    }
}
