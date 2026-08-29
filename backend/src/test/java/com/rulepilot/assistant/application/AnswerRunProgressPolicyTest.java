package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
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
                        AnswerStatus.CLARIFICATION_REQUIRED, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.QUESTION_UNDERSTANDING)))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.NEED_CLARIFICATION);
    }

    @Test
    void recovered_clarification_keeps_the_retrieval_phases_that_actually_ran() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.CLARIFICATION_REQUIRED, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.VERIFYING_EVIDENCE)))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.NEED_CLARIFICATION);
    }

    @Test
    void reports_evidence_insufficiency_after_the_source_scope_has_been_checked() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.INSUFFICIENT_EVIDENCE, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.VERIFYING_EVIDENCE)))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.INSUFFICIENT_EVIDENCE);
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.VERSION_CONFLICT, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.VERIFYING_EVIDENCE)))
                .endsWith(AssistantRunState.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void a_precomposition_failure_stops_at_the_actual_evidence_phase() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.MODEL_TIMEOUT, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.VERIFYING_EVIDENCE)))
                .containsExactly(
                        AssistantRunState.QUESTION_UNDERSTANDING,
                        AssistantRunState.RETRIEVAL_PLANNING,
                        AssistantRunState.RETRIEVING,
                        AssistantRunState.VERIFYING_EVIDENCE,
                        AssistantRunState.DEGRADED)
                .doesNotContain(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.CRITIQUING);
    }

    @Test
    void adds_critique_only_when_the_review_phase_was_actually_reached() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.ANSWERED, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.CRITIQUING)))
                .containsSequence(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.CRITIQUING, AssistantRunState.COMPLETED);
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.ANSWERED, AnswerConfidence.LOW),
                        AnswerRunProgressPolicy.ExecutionPhase.ANSWER_COMPOSITION)))
                .containsSequence(AssistantRunState.ANSWER_COMPOSITION, AssistantRunState.COMPLETED)
                .doesNotContain(AssistantRunState.CRITIQUING);
    }

    @Test
    void confirmed_or_cached_answers_do_not_invent_retrieval_and_composition() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(
                        answer(AnswerStatus.ANSWERED, AnswerConfidence.HIGH),
                        AnswerRunProgressPolicy.ExecutionPhase.QUESTION_UNDERSTANDING)))
                .containsExactly(AssistantRunState.QUESTION_UNDERSTANDING, AssistantRunState.COMPLETED);
    }

    @Test
    void records_a_warned_answer_as_a_completed_degraded_result() {
        assertThat(states(AnswerRunProgressPolicy.updatesFor(answer(
                        AnswerStatus.ANSWERED_WITH_WARNING, AnswerConfidence.HIGH),
                        AnswerRunProgressPolicy.ExecutionPhase.CRITIQUING)))
                .endsWith(AssistantRunState.DEGRADED)
                .doesNotContain(AssistantRunState.INSUFFICIENT_EVIDENCE);
    }

    private List<AssistantRunState> states(List<AnswerRunProgressPolicy.ProgressUpdate> updates) {
        return updates.stream().map(AnswerRunProgressPolicy.ProgressUpdate::state).toList();
    }

    private StructuredRuleAnswer answer(AnswerStatus status, AnswerConfidence confidence) {
        List<RuleCitation> citations = status.publishesConclusion()
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
                null,
                false,
                null,
                null,
                status == AnswerStatus.CLARIFICATION_REQUIRED ? "请补充信息。" : null,
                status == AnswerStatus.ANSWERED_WITH_WARNING
                        ? List.of(new AnswerWarning(AnswerWarning.Type.REVIEW_UNAVAILABLE))
                        : List.of());
    }
}
