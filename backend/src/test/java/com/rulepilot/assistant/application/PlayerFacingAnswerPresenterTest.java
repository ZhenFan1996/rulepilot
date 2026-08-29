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
    void removesInternalAnswerIdentitiesAndPublishesTheAlreadySafeCitationExcerpt() throws Exception {
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
                        "Move the cobalt spindle after the amber gate closes.",
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
                answer, "When does the cobalt spindle move?", PlayerLocale.EN);
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
                .doesNotContain("documentVersionId", "chunkId", "confirmedRulingId");
    }

    @Test
    void preservesAnOptionalDetailThatOnlyContainsOrdinaryProtocolLikeVocabulary() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "Move one space.",
                "The cited rule directly allows one space of movement.",
                List.of(new RuleCitation(chunkId, versionId, "MOVE", "Movement", "Move one space.", 4, 4)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new RuleWalkthroughStep(
                        "Call assistantRunId before moving.",
                        "Internal protocol detail.",
                        WalkthroughOrderBasis.EXPLANATION_ORDER,
                        List.of(chunkId))));

        var presented = PlayerFacingAnswerPresenter.present(answer, "How far may I move?", PlayerLocale.EN);

        assertThat(presented.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(presented.shortVerdict()).isEqualTo("Move one space.");
        assertThat(presented.explanation()).isEqualTo("The cited rule directly allows one space of movement.");
        assertThat(presented.walkthroughSteps()).singleElement().satisfies(step -> {
            assertThat(step.instruction()).isEqualTo("Call assistantRunId before moving.");
            assertThat(step.explanation()).isEqualTo("Internal protocol detail.");
        });
    }

    @Test
    void preservesNaturalRulebookVocabularyInCoreAndCitationInsteadOfTreatingItAsProtocol() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String verdict = "Take one piece of ore.";
        String explanation = "The cited collection rule gives that amount.";
        String excerpt = "Take one ore chunk from the supply.";
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                verdict,
                explanation,
                List.of(new RuleCitation(chunkId, versionId, "COLLECTION", "Collect ore", excerpt, 4, 4)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null);

        var presented = PlayerFacingAnswerPresenter.present(answer, "How much ore do I take?", PlayerLocale.EN);

        assertThat(presented.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(presented.shortVerdict()).isEqualTo(verdict);
        assertThat(presented.explanation()).isEqualTo(explanation);
        assertThat(presented.citations()).singleElement().satisfies(citation ->
                assertThat(citation.excerpt()).isEqualTo(excerpt));
    }

    @Test
    void preservesValidatedModelProseByteForByteIncludingFormattingAndPunctuation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String verdict = "【裁决】可以——但要先满足条件。";
        String explanation = "【理由】**原规则**明确写出了条件。\n【边界】这里只裁决当前局面；不扩写别的情况。";
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                verdict,
                explanation,
                List.of(new RuleCitation(chunkId, versionId, "RULE", "条件", "满足条件后可以执行。", 4, 4)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null);

        var presented = PlayerFacingAnswerPresenter.present(answer, "这个局面可以吗？", PlayerLocale.ZH_CN);

        assertThat(presented.shortVerdict()).isEqualTo(verdict);
        assertThat(presented.explanation()).isEqualTo(explanation);
    }

    @Test
    void presentsDistinctModelFailuresAndRetrySuitabilityWithoutInternalDiagnostics() {
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
                failure, "Why does the silver lattice resolve first?", PlayerLocale.EN);
        var unavailable = PlayerFacingAnswerPresenter.present(
                new StructuredRuleAnswer(
                        versionId,
                        AnswerStatus.MODEL_UNAVAILABLE,
                        "provider configuration failed",
                        "secret provider diagnostic",
                        List.of(),
                        List.of(),
                        AnswerConfidence.LOW,
                        false,
                        null,
                        null,
                        null),
                "Why does the silver lattice resolve first?",
                PlayerLocale.EN);
        var timeout = PlayerFacingAnswerPresenter.present(
                new StructuredRuleAnswer(
                        versionId,
                        AnswerStatus.MODEL_TIMEOUT,
                        "provider timeout",
                        "secret timeout diagnostic",
                        List.of(),
                        List.of(),
                        AnswerConfidence.LOW,
                        false,
                        null,
                        null,
                        null),
                "Why does the silver lattice resolve first?",
                PlayerLocale.EN);

        assertThat(presented.language()).isEqualTo("en");
        assertThat(presented.shortVerdict())
                .isEqualTo("The generated answer failed its structure or citation-identifier contract.");
        assertThat(presented.explanation()).isEmpty();
        assertThat(presented.recovery()).isNotNull().satisfies(recovery -> {
            assertThat(recovery.message()).contains("unlikely to help", "review or rephrase");
            assertThat(recovery.actionLabel()).isEqualTo("Review or rephrase");
            assertThat(recovery.draft()).isEqualTo("Why does the silver lattice resolve first?");
            assertThat(recovery.canRetryUnchanged()).isFalse();
        });
        assertThat(unavailable.shortVerdict()).contains("No configured answer model or provider");
        assertThat(unavailable.recovery()).satisfies(recovery -> {
            assertThat(recovery.message()).contains("retry the same question unchanged");
            assertThat(recovery.canRetryUnchanged()).isTrue();
        });
        assertThat(timeout.shortVerdict()).contains("answer did not finish", "time limit");
        assertThat(timeout.recovery()).satisfies(recovery -> {
            assertThat(recovery.message()).contains("retry the same question unchanged");
            assertThat(recovery.canRetryUnchanged()).isTrue();
        });
        assertThat(presented.toString())
                .doesNotContain("repairRuleTimingResolutions", "schema", "时序裁决")
                .doesNotContainPattern("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    }

    @Test
    void preservesModelClarificationAndThePlayersOwnRecoveryDraftWithoutKeywordCensorship() {
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

        assertThat(clarification.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(clarification.clarification())
                .isEqualTo("Schema output validation failed; call repairRuleSituationCheck.");
        assertThat(invalid.recovery().draft()).isEqualTo("Retry assistantRunId " + internalId);
    }

    @Test
    void preservesAValidatedClarificationExactly() {
        String clarification = "\n你说的“这个”具体是哪张卡、哪个行动或哪个效果？\n";
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
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
                clarification);

        var presented = PlayerFacingAnswerPresenter.present(answer, "这个能用吗？", PlayerLocale.ZH_CN);

        assertThat(presented.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(presented.clarification()).isEqualTo(clarification);
    }

    @Test
    void preservesAChineseClarificationWithoutTryingToClassifyItsVocabulary() {
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

        assertThat(presented.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(presented.clarification()).isEqualTo("模型输出校验失败，请重试系统提示词。 ");
    }
}
