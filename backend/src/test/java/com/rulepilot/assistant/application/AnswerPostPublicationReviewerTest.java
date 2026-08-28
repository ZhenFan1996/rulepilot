package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerPostPublicationReviewerTest {

    @Test
    void criticFindingIsDiagnosticAndPreservesTheCompleteValidatedAnswer() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = source(versionId);
        StructuredRuleAnswer answer = answer(versionId, source);
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer(
                (request, risk) -> new GeneratedContentCritic.Review(
                        true,
                        List.of(new GeneratedContentCritic.Issue(
                                GeneratedContentCritic.IssueType.OVERREACH,
                                1,
                                List.of(source.chunkId()),
                                "Evaluation finding only."))));

        StructuredRuleAnswer result = reviewer.review(
                UUID.randomUUID(),
                understood(versionId),
                new QuestionContext(versionId),
                "player",
                request(source),
                answer,
                List.of(new HybridEvidenceHit(source, 0.1, 1, null, false)));

        assertThat(result.shortVerdict()).isEqualTo(answer.shortVerdict());
        assertThat(result.explanation()).isEqualTo(answer.explanation());
        assertThat(result.citations()).isEqualTo(answer.citations());
        assertThat(result.warnings())
                .extracting(AnswerWarning::type)
                .contains(AnswerWarning.Type.REVIEW_UNRESOLVED);
    }

    @Test
    void unavailableOptionalCriticDoesNotEraseTheValidatedAnswer() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = source(versionId);
        StructuredRuleAnswer answer = answer(versionId, source);
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer((request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        });

        StructuredRuleAnswer result = reviewer.review(
                UUID.randomUUID(),
                understood(versionId),
                new QuestionContext(versionId),
                "player",
                request(source),
                answer,
                List.of(new HybridEvidenceHit(source, 0.1, 1, null, false)));

        assertThat(result.shortVerdict()).isEqualTo(answer.shortVerdict());
        assertThat(result.explanation()).isEqualTo(answer.explanation());
        assertThat(result.warnings())
                .extracting(AnswerWarning::type)
                .contains(AnswerWarning.Type.REVIEW_UNRESOLVED);
    }

    private static RuleEvidenceHit source(UUID versionId) {
        return new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Action timing",
                "Take the main action once.",
                4,
                4,
                0.9);
    }

    private static StructuredRuleAnswer answer(UUID versionId, RuleEvidenceHit source) {
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "You may take the main action once.",
                "The cited action rule states one main action.",
                List.of(new RuleCitation(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo())),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null);
    }

    private static UnderstoodQuestion understood(UUID versionId) {
        return new UnderstoodQuestion(
                versionId,
                "How often can I take the main action?",
                "How often can I take the main action?",
                QuestionType.RULE_QUERY,
                List.of("main action"),
                Set.of());
    }

    private static ModelRequest request(RuleEvidenceHit source) {
        return new ModelRequest(
                "How often can I take the main action?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        source.chunkId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo())));
    }
}
