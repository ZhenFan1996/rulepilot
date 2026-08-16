package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerBasisPolicyTest {

    @Test
    void classifiesOnlyApplicationControlledArithmeticAsAGroundedApplication() {
        var request = request("我有 8 个资源，可以得到多少分？");
        var draft = new RuleAnswerModel.ModelDraft(
                true,
                null,
                "得到 10 分。",
                "按引用的计分公式计算。",
                List.of(UUID.randomUUID()),
                List.of(),
                "HIGH",
                "DIRECT_RULE",
                List.of(new RuleAnswerModel.CalculationRequest("floor(8 / 3) * 5")));

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

    @Test
    void replacesUntrustedFreeTextBasisWithTheApplicationClassification() {
        var request = request("When does the round end?");
        var draft = new RuleAnswerModel.ModelDraft(
                true,
                null,
                "At the stated round boundary.",
                "The cited rule gives the boundary.",
                List.of(UUID.randomUUID()),
                List.of(),
                "HIGH",
                "direct rule statement from the chapter",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThat(AnswerBasisPolicy.classify(request, draft).answerBasis()).isEqualTo("DIRECT_RULE");
    }

    @Test
    void ignoresModelProposedSituationChecksWhenClassifyingAnAbstractRuleAnswer() {
        UUID citationId = UUID.randomUUID();
        var request = request("When does the round end?");
        var draft = new RuleAnswerModel.ModelDraft(
                true,
                null,
                "The round ends after cleanup.",
                "The cited rule defines the boundary.",
                List.of(citationId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION",
                List.of(),
                List.of(new RuleAnswerModel.SituationCheckRequest(
                        "The round has reached cleanup.", "SATISFIED", "Cleanup has started.", List.of(citationId))));

        assertThat(AnswerBasisPolicy.classify(request, draft).answerBasis()).isEqualTo("DIRECT_RULE");
    }

    @Test
    void classifiesASelectedCitedScopeApplicationAsGroundedApplication() {
        UUID citationId = UUID.randomUUID();
        var request = new RuleAnswerModel.ModelRequest(
                "两名玩家时这条规则适用吗？",
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(
                        null, null, com.rulepilot.assistant.PlayerLocale.ZH_CN),
                List.of(new RuleAnswerModel.EvidenceInput(
                        citationId, "RULE", "人数限制", "两名玩家时不能使用这项规则。", 2, 2)),
                Set.of(EvidenceNeed.CONDITION),
                AnswerAid.SCOPE);
        var draft = new RuleAnswerModel.ModelDraft(
                true,
                null,
                "两名玩家时不适用。",
                "当前局面符合引用中的人数限制。",
                List.of(citationId),
                List.of(),
                "HIGH",
                "DIRECT_RULE",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new RuleScopeRequest(
                        "两人局",
                        "玩家数为二",
                        "当前问题明确是两名玩家",
                        "MATCHES_SCOPE",
                        "不能使用这项规则",
                        "PLAYER_COUNT",
                        List.of(citationId))));

        assertThat(AnswerBasisPolicy.classify(request, draft).answerBasis())
                .isEqualTo("GROUNDED_APPLICATION");
    }

    private RuleAnswerModel.ModelRequest request(String question) {
        return new RuleAnswerModel.ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, com.rulepilot.assistant.PlayerLocale.ZH_CN),
                List.of(new RuleAnswerModel.EvidenceInput(UUID.randomUUID(), "ACTIONS", "Actions", "Take a row.", 1, 1)));
    }
}
