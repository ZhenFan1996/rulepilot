package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRepairOutcomePolicyTest {

    @Test
    void retries_one_inactive_actor_repair_only_when_the_evidence_names_a_successor_rule() {
        UUID chunkId = UUID.randomUUID();
        List<String> inactiveActorFeedback = List.of("INACTIVE_ACTOR: use the stated successor.");
        ModelRequest successorRequest = request(
                "Who leads next?",
                new EvidenceInput(
                        chunkId,
                        "RULES",
                        "Next trick",
                        "If a player is out of cards, the next player to the left starts the next trick.",
                        9,
                        9));
        ModelRequest ordinaryRequest = request(
                "Who leads next?",
                new EvidenceInput(chunkId, "RULES", "Next trick", "Continue the turn.", 9, 9));
        ModelDraft unanswerable = new ModelDraft(false, "missing", null, null, List.of(), List.of(), "LOW");

        assertThat(AnswerRepairOutcomePolicy.shouldRetryWithEvidencedSuccessor(
                        successorRequest, unanswerable, inactiveActorFeedback))
                .isTrue();
        assertThat(AnswerRepairOutcomePolicy.shouldRetryWithEvidencedSuccessor(
                        ordinaryRequest, unanswerable, inactiveActorFeedback))
                .isFalse();
        assertThat(AnswerRepairOutcomePolicy.shouldRetryWithEvidencedSuccessor(
                        successorRequest, answerable(chunkId), inactiveActorFeedback))
                .isFalse();
        assertThat(AnswerRepairOutcomePolicy.shouldRetryWithEvidencedSuccessor(
                        successorRequest, unanswerable, List.of("VISUAL_IDENTITY: reconcile icon")))
                .isFalse();
    }

    @Test
    void builds_the_second_successor_repair_without_losing_the_first_repair_context() {
        List<String> feedback = List.of("INACTIVE_ACTOR: use the stated successor.");

        ModelDraft retryDraft = AnswerRepairOutcomePolicy.retryDraft(null);

        assertThat(retryDraft.answerable()).isFalse();
        assertThat(retryDraft.insufficiencyReason()).isEqualTo("First repair did not produce a draft");
        assertThat(AnswerRepairOutcomePolicy.successorRetryFeedback(feedback)).containsExactly(
                "INACTIVE_ACTOR: use the stated successor.",
                "EVIDENCED_SUCCESSOR_RULE: The supplied evidence explicitly contains both the state-change condition "
                        + "and its replacement or successor actor. Apply that exact conditional rule directly; do not "
                        + "abstain and do not fall back to the default actor.");
    }

    @Test
    void keeps_player_safe_insufficiency_wording_specific_to_the_failed_repair() {
        assertThat(AnswerRepairOutcomePolicy.insufficientRepairMessage(List.of("INACTIVE_ACTOR: successor")))
                .isEqualTo("现有证据未能确定状态变化后的下一位行动者。");
        assertThat(AnswerRepairOutcomePolicy.insufficientRepairMessage(List.of("VISUAL_IDENTITY: icon")))
                .isEqualTo("图标对应的规则资源无法从现有证据中可靠确定。");
    }

    @Test
    void retains_the_existing_publication_guard_order_after_repair() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest request = request(
                "What happens next?",
                new EvidenceInput(chunkId, "RULES", "Next", "After this action, continue the turn.", 3, 3));
        ModelDraft internalReference = new ModelDraft(
                "See [E12].", "Continue the turn.", List.of(chunkId), List.of(), "HIGH");
        ModelDraft conflated = new ModelDraft(
                "需要资源。", "该图标表示至少两张手牌才能发动。", List.of(chunkId), List.of(), "HIGH");
        ModelDraft inactiveActor = new ModelDraft(
                "继续本轮。", "手牌为空后，你领出下一墩。", List.of(chunkId), List.of(), "HIGH");
        ModelDraft safe = answerable(chunkId);

        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, internalReference))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。"));
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, conflated))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT, "回答混淆了规则资源与手牌数量，未向玩家发布。"));
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, inactiveActor))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT, "回答让已退出当前流程的玩家继续行动，未向玩家发布。"));
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, safe)).isEmpty();
    }

    private ModelRequest request(String question, EvidenceInput evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(evidence));
    }

    private ModelDraft answerable(UUID chunkId) {
        return new ModelDraft(
                "继续行动。", "按规则继续行动。", List.of(chunkId), List.of(), "HIGH");
    }
}
