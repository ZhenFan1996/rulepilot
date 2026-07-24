package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerBasisPolicyTest {

    @Test
    void classifiesAConclusionUsingThePlayersCompletedTableActionAsAGroundedApplication() {
        var request = request("我刚拿走左边一列并补好牌，下一位能从哪里拿牌？");
        var draft = new RuleAnswerModel.ModelDraft(
                "下一位选择规则允许的位置。", "按当前标记位置套用取牌限制。",
                List.of(UUID.randomUUID()), List.of(), "HIGH");

        var classified = AnswerBasisPolicy.classify(request, draft);

        assertThat(classified.answerBasis()).isEqualTo("GROUNDED_APPLICATION");
    }

    @Test
    void keepsAnAbstractRuleQuestionAsADirectRule() {
        var request = request("每回合可以拿几张牌？");
        var draft = new RuleAnswerModel.ModelDraft(
                "必须拿一整行或一整列。", "一次拿三张。",
                List.of(UUID.randomUUID()), List.of(), "HIGH");

        assertThat(AnswerBasisPolicy.classify(request, draft).answerBasis()).isEqualTo("DIRECT_RULE");
    }

    private RuleAnswerModel.ModelRequest request(String question) {
        return new RuleAnswerModel.ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, null, 0),
                List.of(new RuleAnswerModel.EvidenceInput(UUID.randomUUID(), "ACTIONS", "Actions", "Take a row.", 1, 1)));
    }
}
