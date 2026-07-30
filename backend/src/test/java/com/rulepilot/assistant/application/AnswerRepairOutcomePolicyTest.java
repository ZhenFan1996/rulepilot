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
    void keeps_player_safe_insufficiency_wording_for_visual_repairs() {
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
        ModelDraft safe = answerable(chunkId);

        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, internalReference))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT, "回答包含内部证据标识，未向玩家发布。"));
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, conflated))
                .contains(new AnswerRepairOutcomePolicy.PublicationFailure(
                        AnswerStatus.INVALID_MODEL_OUTPUT, "回答混淆了规则资源与手牌数量，未向玩家发布。"));
        assertThat(AnswerRepairOutcomePolicy.publicationFailure(request, safe)).isEmpty();
    }

    private ModelRequest request(String question, EvidenceInput evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, PlayerLocale.ZH_CN),
                List.of(evidence));
    }

    private ModelDraft answerable(UUID chunkId) {
        return new ModelDraft(
                "继续行动。", "按规则继续行动。", List.of(chunkId), List.of(), "HIGH");
    }
}
