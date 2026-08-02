package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerOutcomePolicyTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void projectsReadableCitationsWhileKeepingEvidenceIdentityInsideTheModuleBoundary() {
        UUID assistantRunId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "先放置标记。",
                "把标记放到起始区域。",
                List.of(new RuleCitation(chunkId, documentVersionId, "SETUP", "设置", "放置标记。", 2, 2)),
                List.of(),
                AnswerConfidence.HIGH,
                false,
                null,
                null,
                null);

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(assistantRunId, answer);

        assertThat(result.assistantRunId()).isEqualTo(assistantRunId);
        assertThat(result.citedEvidenceIds()).containsExactly(chunkId);
        assertThat(result.answer()).satisfies(publicAnswer -> {
            assertThat(publicAnswer.status()).isEqualTo("ANSWERED");
            assertThat(publicAnswer.answerBasis()).isEqualTo("DIRECT_RULE");
            assertThat(publicAnswer.citations()).containsExactly(new RuleAnswering.Citation("设置", 2, 2));
        });
    }

    @Test
    void mapsAConfirmedRulingWithItsVersionedIdentityAndOfficialStatus() {
        UUID rulingId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = AnswerOutcomePolicy.confirmedRuling(new ConfirmedRulingLookup.ConfirmedAnswer(
                rulingId,
                documentVersionId,
                "使用官方裁定。",
                "按最新裁定处理。",
                List.of(new ConfirmedRulingLookup.Citation(
                        chunkId, documentVersionId, "FAQ", "官方裁定", "按最新裁定处理。", 9, 9)),
                List.of("只适用于当前扩展。"),
                "MEDIUM",
                true,
                4));

        assertThat(answer).satisfies(mapped -> {
            assertThat(mapped.status()).isEqualTo(AnswerStatus.ANSWERED);
            assertThat(mapped.official()).isTrue();
            assertThat(mapped.confirmedRulingId()).isEqualTo(rulingId);
            assertThat(mapped.confirmedRulingVersion()).isEqualTo(4);
            assertThat(mapped.citations()).extracting(RuleCitation::chunkId).containsExactly(chunkId);
        });
    }

    @Test
    void requestsMissingContextInStablePlayerReadableOrder() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                documentVersionId,
                "我可以这样做吗？",
                "我可以这样做吗？",
                QuestionType.SITUATION_QUERY,
                List.of(),
                Set.of(MissingQuestionContext.SITUATION_DETAILS));

        StructuredRuleAnswer answer = AnswerOutcomePolicy.clarification(question);

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.explanation()).isEqualTo("缺少信息：SITUATION_DETAILS");
        assertThat(answer.clarification()).isEqualTo("请补充 SITUATION_DETAILS。");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void createsAUniformSafeFailureWithoutPublishingRuleEvidence() {
        StructuredRuleAnswer answer = AnswerOutcomePolicy.safeFailure(
                documentVersionId, AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试。 ");

        assertThat(answer).satisfies(safe -> {
            assertThat(safe.shortVerdict()).isEqualTo("回答生成超时，可以稍后重试。 ");
            assertThat(safe.explanation()).isEqualTo("回答生成超时，可以稍后重试。 ");
            assertThat(safe.confidence()).isEqualTo(AnswerConfidence.LOW);
            assertThat(safe.citations()).isEmpty();
            assertThat(safe.answerBasis()).isNull();
        });
    }

    @Test
    void qualifiesAnEvidenceScopedAnswerWithoutDiscardingItsConclusion() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "先放置标记。",
                "把标记放到起始区域。",
                List.of(new RuleCitation(chunkId, documentVersionId, "SETUP", "设置", "放置标记。", 2, 2)),
                List.of(),
                AnswerConfidence.HIGH,
                false,
                null,
                null,
                null);

        StructuredRuleAnswer warned = AnswerOutcomePolicy.withWarnings(
                answer, List.of(new AnswerWarning(AnswerWarning.Type.REVIEW_UNAVAILABLE)));

        assertThat(warned.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(warned.shortVerdict()).isEqualTo(answer.shortVerdict());
        assertThat(warned.citations()).isEqualTo(answer.citations());
        assertThat(warned.warnings()).extracting(AnswerWarning::type)
                .containsExactly(AnswerWarning.Type.REVIEW_UNAVAILABLE);
    }

    @Test
    void exposesBoundedSourcesForInsufficiencyWithoutPublishingAConclusion() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), documentVersionId, "RULES", "相关规则", "规则原文。", 3, 3, 0.8);

        StructuredRuleAnswer answer = AnswerOutcomePolicy.insufficientWithSources(
                documentVersionId,
                "现有证据未能直接回答这个问题。",
                List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.answerBasis()).isNull();
        assertThat(answer.citations()).extracting(RuleCitation::chunkId).containsExactly(source.chunkId());
    }
}
