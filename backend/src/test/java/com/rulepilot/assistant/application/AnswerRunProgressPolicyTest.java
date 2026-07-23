package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantRunState;
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
    void stops_after_clarification_without_claiming_retrieval_or_composition() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.CLARIFICATION_REQUIRED, AnswerConfidence.LOW))))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.NEED_CLARIFICATION);
    }

    @Test
    void reports_evidence_insufficiency_after_the_source_scope_has_been_checked() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.INSUFFICIENT_EVIDENCE, AnswerConfidence.LOW))))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.INSUFFICIENT_EVIDENCE);
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.VERSION_CONFLICT, AnswerConfidence.LOW))))
                .endsWith(AssistantRunState.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void marks_non_answered_generation_as_degraded_after_composition() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.MODEL_TIMEOUT, AnswerConfidence.LOW))))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.ANSWER_COMPOSITION,
                        AssistantRunState.DEGRADED);
    }

    @Test
    void adds_critique_only_for_a_low_confidence_completed_answer() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(AnswerStatus.ANSWERED, AnswerConfidence.LOW))))
                .containsSequence(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.CRITIQUING, AssistantRunState.COMPLETED);
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(AnswerStatus.ANSWERED, AnswerConfidence.HIGH))))
                .containsSequence(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.COMPLETED)
                .doesNotContain(AssistantRunState.CRITIQUING);
    }

    private List<AssistantRunState> states(List<AnswerRunProgressPolicy.ProgressUpdate> updates) {
        return updates.stream().map(AnswerRunProgressPolicy.ProgressUpdate::state).toList();
    }

    private StructuredRuleAnswer answer(AnswerStatus status, AnswerConfidence confidence) {
        List<RuleCitation> citations = status == AnswerStatus.ANSWERED
                ? List.of(new RuleCitation(
                        UUID.randomUUID(), versionId, "RULES", "Rule", "Follow the rule.", 1, 1))
                : List.of();
        return new StructuredRuleAnswer(
                versionId,
                status,
                "结果。",
                "依据规则给出结果。",
                citations,
                List.of(),
                confidence,
                false,
                null,
                null,
                status == AnswerStatus.CLARIFICATION_REQUIRED ? "请补充信息。" : null);
    }
}
