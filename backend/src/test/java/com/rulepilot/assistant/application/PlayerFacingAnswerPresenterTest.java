package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerFacingAnswerPresenterTest {

    @Test
    void publishesValidatedModelProseVerbatimAndOnlyMapsCitationDisplayFields() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String verdict = "【裁决】可以——但要先满足条件。";
        String explanation = "【理由】**原规则**明确写出了条件。\n【边界】不扩写别的情况。";
        String clarification = "尚未确认的部分发生在哪个时机？";
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                verdict,
                explanation,
                List.of(new RuleCitation(
                        evidenceId, versionId, "RULE", "条件", "满足条件后可以执行。", 4, 4)),
                List.of("只适用于当前时机。"),
                AnswerConfidence.MEDIUM,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                clarification);

        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                answer, "这个局面可以吗？", PlayerLocale.ZH_CN);
        String json = new ObjectMapper().writeValueAsString(presented);

        assertThat(presented.shortVerdict()).isEqualTo(verdict);
        assertThat(presented.explanation()).isEqualTo(explanation);
        assertThat(presented.clarification()).isEqualTo(clarification);
        assertThat(presented.exceptions()).containsExactly("只适用于当前时机。");
        assertThat(presented.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.heading()).isEqualTo("条件");
            assertThat(citation.excerpt()).isEqualTo("满足条件后可以执行。");
        });
        assertThat(presented.calculations()).isEmpty();
        assertThat(presented.walkthroughSteps()).isEmpty();
        assertThat(json).doesNotContain(versionId.toString(), evidenceId.toString());
    }

    @Test
    void publishesAChatTerminalWithoutInventingCitationOrAnswerBasis() {
        StructuredRuleAnswer chat = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.ANSWERED,
                "你好！很高兴帮你查规则。",
                "",
                List.of(),
                List.of(),
                AnswerConfidence.MEDIUM,
                null,
                false,
                null,
                null,
                null);

        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                chat, "你好", PlayerLocale.ZH_CN);

        assertThat(presented.shortVerdict()).isEqualTo("你好！很高兴帮你查规则。");
        assertThat(presented.explanation()).isEmpty();
        assertThat(presented.citations()).isEmpty();
        assertThat(presented.answerBasis()).isNull();
        assertThat(presented.recovery()).isNull();
    }

    @Test
    void publishesTheAgentsCompleteClarificationWithoutARecoveryTemplate() {
        String verdict = "还缺一项信息。";
        String explanation = "两种时机适用不同条款。";
        String clarification = "你说的是打出卡牌时，还是解决卡牌效果时？";
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.CLARIFICATION_REQUIRED,
                verdict,
                explanation,
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                null,
                false,
                null,
                null,
                clarification);

        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                answer, "这时可以吗？", PlayerLocale.ZH_CN);

        assertThat(presented.shortVerdict()).isEqualTo(verdict);
        assertThat(presented.explanation()).isEqualTo(explanation);
        assertThat(presented.clarification()).isEqualTo(clarification);
        assertThat(presented.recovery()).isNull();
    }

    @Test
    void preservesTheExactAgentStopMessageAndDoesNotBlameOrRewriteTheQuestion() {
        String stopMessage = "Agent 在收到 JSON 或证据身份校验结果后仍重复同一份完整回复，因此已停止修正。";
        StructuredRuleAnswer failure = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.INVALID_MODEL_OUTPUT,
                stopMessage,
                stopMessage,
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                null,
                false,
                null,
                null,
                null);

        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                failure, "这个行动可以吗？", PlayerLocale.ZH_CN);

        assertThat(presented.shortVerdict()).isEqualTo(stopMessage);
        assertThat(presented.explanation()).isEqualTo(stopMessage);
        assertThat(presented.recovery()).isNotNull().satisfies(recovery -> {
            assertThat(recovery.message()).contains("JSON", "证据身份", "问题本身没有被拒绝");
            assertThat(recovery.actionLabel()).isEqualTo("查看 Agent 结果");
            assertThat(recovery.canRetryUnchanged()).isFalse();
        });
    }
}
