package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerFacingAnswerPresenterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void removesInternalAnswerIdentitiesAndVisualInstructionsWithoutLosingReadableEvidence() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID rulingId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "Move the cobalt spindle after the amber gate closes.",
                "The cited timing clause puts the move after that boundary.",
                List.of(new RuleCitation(
                        chunkId,
                        versionId,
                        "TIMING",
                        "Amber gate",
                        "Visual-transcribed rule evidence. Only the statements under Visible rule facts are rule evidence. "
                                + "Do not derive a timing rule from layout alone.\nVisible rule facts: "
                                + "Move the cobalt spindle after the amber gate closes.",
                        7,
                        7)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                true,
                rulingId,
                3L,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new RuleWalkthroughStep(
                        "Move the cobalt spindle.",
                        "Resolve the amber gate first.",
                        WalkthroughOrderBasis.RULE_ORDER,
                        List.of(chunkId))));

        var presented = PlayerFacingAnswerPresenter.present(
                answer, "When does the cobalt spindle move?", PlayerLocale.ZH_CN);
        String serialized = json.writeValueAsString(presented);

        assertThat(presented.language()).isEqualTo("en");
        assertThat(presented.source()).isEqualTo(PlayerFacingRuleAnswer.SourceKind.CONFIRMED);
        assertThat(presented.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.heading()).isEqualTo("Amber gate");
            assertThat(citation.excerpt()).isEqualTo("Move the cobalt spindle after the amber gate closes.");
        });
        assertThat(presented.walkthroughSteps()).singleElement().satisfies(step ->
                assertThat(step.instruction()).isEqualTo("Move the cobalt spindle."));
        assertThat(serialized)
                .doesNotContain(versionId.toString(), chunkId.toString(), rulingId.toString())
                .doesNotContain("documentVersionId", "chunkId", "confirmedRulingId")
                .doesNotContain("Visual-transcribed rule evidence", "Do not derive");
    }

    @Test
    void failsClosedWhenALegacyVisualEnvelopeHasNoReadableFactBoundary() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "Use the printed order.",
                "The cited rule lists the order.",
                List.of(new RuleCitation(
                        chunkId,
                        versionId,
                        "ORDER",
                        "Printed order",
                        "Visual-transcribed rule evidence. Do not expose this internal instruction.",
                        3,
                        3)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null);

        var presented = PlayerFacingAnswerPresenter.present(answer, "What is the printed order?", PlayerLocale.EN);

        assertThat(presented.citations()).singleElement().satisfies(citation ->
                assertThat(citation.excerpt()).isEmpty());
        assertThat(json.writeValueAsString(presented))
                .doesNotContain("Visual-transcribed", "internal instruction", chunkId.toString());
    }

    @Test
    void usesTheCurrentQuestionLanguageForSafeFailureAndRecoveryInsteadOfInternalDiagnostics() {
        UUID versionId = UUID.randomUUID();
        StructuredRuleAnswer failure = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.INVALID_MODEL_OUTPUT,
                "时序裁决未通过情境、顺序、来源或引用校验。",
                "repairRuleTimingResolutions rejected schema output for " + UUID.randomUUID(),
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                null);

        var presented = PlayerFacingAnswerPresenter.present(
                failure, "Why does the silver lattice resolve first?", PlayerLocale.ZH_CN);

        assertThat(presented.language()).isEqualTo("en");
        assertThat(presented.shortVerdict()).isEqualTo("I couldn't verify a reliable answer from this attempt.");
        assertThat(presented.explanation()).isEmpty();
        assertThat(presented.recovery()).isNotNull().satisfies(recovery -> {
            assertThat(recovery.message()).contains("question is still here");
            assertThat(recovery.actionLabel()).isEqualTo("Review and try again");
            assertThat(recovery.draft()).isEqualTo("Why does the silver lattice resolve first?");
        });
        assertThat(presented.toString())
                .doesNotContain("repairRuleTimingResolutions", "schema", "时序裁决")
                .doesNotContainPattern("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    }

    @Test
    void keepsAChineseCurrentTurnChineseWhenTheUiFallbackIsEnglish() {
        StructuredRuleAnswer failure = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.INSUFFICIENT_EVIDENCE,
                "internal evidence admission diagnostic",
                "internal evidence admission diagnostic",
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                null);

        var presented = PlayerFacingAnswerPresenter.present(
                failure, "青色棱柱什么时候结算？", PlayerLocale.EN);

        assertThat(presented.language()).isEqualTo("zh-CN");
        assertThat(presented.shortVerdict()).isEqualTo("现有依据还不足以可靠回答这个问题。");
        assertThat(presented.recovery().actionLabel()).isEqualTo("补充细节");
        assertThat(presented.toString()).doesNotContain("internal evidence admission diagnostic");
    }

    @Test
    void replacesSchemaDiagnosticsAndNeverCopiesProtocolIdentifiersIntoARecoveryDraft() {
        UUID internalId = UUID.randomUUID();
        StructuredRuleAnswer failure = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.CLARIFICATION_REQUIRED,
                "CLARIFICATION_REQUIRED",
                "schema output invalid",
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                "Schema output validation failed; call repairRuleSituationCheck.");

        var clarification = PlayerFacingAnswerPresenter.present(
                failure, "What does the silver dial refer to?", PlayerLocale.EN);
        var invalid = PlayerFacingAnswerPresenter.present(
                new StructuredRuleAnswer(
                        UUID.randomUUID(),
                        AnswerStatus.INVALID_MODEL_OUTPUT,
                        "invalid",
                        "invalid",
                        List.of(),
                        List.of(),
                        AnswerConfidence.LOW,
                        false,
                        null,
                        null,
                        null),
                "Retry assistantRunId " + internalId,
                PlayerLocale.EN);

        assertThat(clarification.shortVerdict())
                .isEqualTo("I couldn't verify a reliable answer from this attempt.");
        assertThat(clarification.recovery().message()).doesNotContainIgnoringCase("schema");
        assertThat(clarification.toString())
                .doesNotContain("CLARIFICATION_REQUIRED", "repairRuleSituationCheck", "Schema output");
        assertThat(invalid.recovery().draft()).isEmpty();
        assertThat(invalid.toString()).doesNotContain(internalId.toString(), "assistantRunId");
    }

    @Test
    void rejectsChinesePromptAndModelDiagnosticsAtThePublicationBoundary() {
        StructuredRuleAnswer leaked = new StructuredRuleAnswer(
                UUID.randomUUID(),
                AnswerStatus.CLARIFICATION_REQUIRED,
                "需要补充信息。",
                "",
                List.of(),
                List.of(),
                AnswerConfidence.LOW,
                false,
                null,
                null,
                "模型输出校验失败，请重试系统提示词。 ");

        var presented = PlayerFacingAnswerPresenter.present(
                leaked, "青色立方体什么时候结算？", PlayerLocale.ZH_CN);

        assertThat(presented.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(presented.shortVerdict()).isEqualTo("这次结果没有通过可靠性核对。");
        assertThat(presented.recovery().message()).doesNotContain("模型输出", "系统提示词");
        assertThat(presented.toString()).doesNotContain("模型输出", "系统提示词");
    }
}
