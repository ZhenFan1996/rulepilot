package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerCritiquePolicyTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void treatsEveryPublishedAnswerAsHighImpactForSemanticReview() {
        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), answer()))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void allowsOneBoundedCorrectionOnlyWithACompleteQuestionContext() {
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(question(), context())).isTrue();
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(null, context())).isFalse();
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(question(), null)).isFalse();
    }

    @Test
    void buildsOneEvidenceBoundSemanticReviewAcrossProseAndStructuredClaims() {
        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question(), context(), answer(), List.of(evidence()));

        assertThat(request.contentType()).isEqualTo(GeneratedContentCritic.ContentType.ANSWER);
        assertThat(request.taskContext().objective()).contains(question().normalizedQuestion());
        assertThat(request.taskContext().requiredCoverage())
                .contains(
                        "actor",
                        "condition",
                        "selected structured aid",
                        "aggregation unit",
                        "multiplier",
                        "worked example",
                        "Natural paraphrase");
        assertThat(request.claims()).extracting(GeneratedContentCritic.Claim::text)
                .containsExactly(
                        "Direct verdict.\nGrounded explanation.",
                        "Cited exception.",
                        "Calculation: 8 / 2 = 4",
                        "Walkthrough; orderBasis=RULE_ORDER; instruction=Pay first.; explanation=Then resolve.");
        assertThat(request.claims()).allSatisfy(claim ->
                assertThat(claim.citationIds()).containsExactly(chunkId));
        assertThat(request.evidence()).containsExactly(
                new GeneratedContentCritic.Evidence(chunkId, "Pay first, then resolve; eight divided by two is four."));
    }

    @Test
    void convertsConcreteCriticIssuesIntoBoundedRevisionFeedback() {
        Review review = new Review(true, List.of(
                new Issue(IssueType.CONTRADICTION, 1, List.of(chunkId), "Reverse the claimed order."),
                new Issue(IssueType.MISSING_EXCEPTION, 2, List.of(chunkId), "Add the cited exception.")));

        assertThat(AnswerCritiquePolicy.revisionFeedback(review)).containsExactly(
                "CONTRADICTION: Reverse the claimed order.",
                "MISSING_EXCEPTION: Add the cited exception.");
    }

    @Test
    void keepsTheCurrentQuestionAuthoritativeWhenResolvedContextContainsAnEarlierObject() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Does the cobalt spindle return now?",
                "How many marks does the amber lattice award? Follow-up: Does the cobalt spindle return now?",
                QuestionType.RULE_QUERY,
                List.of("cobalt spindle"),
                Set.of());

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question, context(), answer(), List.of(evidence()));

        assertThat(request.taskContext().objective())
                .contains("Does the cobalt spindle return now?")
                .doesNotContain("amber lattice");
    }

    private UnderstoodQuestion question() {
        return new UnderstoodQuestion(
                versionId,
                "How does this procedure work?",
                "How does this procedure work?",
                QuestionType.RULE_QUERY,
                List.of(),
                Set.of());
    }

    private QuestionContext context() {
        return new QuestionContext(versionId, null, null, PlayerLocale.EN);
    }

    private StructuredRuleAnswer answer() {
        RuleCitation citation = new RuleCitation(
                chunkId, versionId, "RULE", "Procedure",
                "Pay first, then resolve; eight divided by two is four.", 3, 3);
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "Direct verdict.",
                "Grounded explanation.",
                List.of(citation),
                List.of("Cited exception."),
                AnswerConfidence.HIGH,
                AnswerBasis.GROUNDED_APPLICATION,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(new RuleCalculation("8 / 2", "4")),
                List.of(),
                List.of(new RuleWalkthroughStep(
                        "Pay first.", "Then resolve.", WalkthroughOrderBasis.RULE_ORDER, List.of(chunkId))));
    }

    private HybridEvidenceHit evidence() {
        return new HybridEvidenceHit(
                new RuleEvidenceHit(
                        chunkId, versionId, "RULE", "Procedure",
                        "Pay first, then resolve; eight divided by two is four.", 3, 3, 0.9),
                0.9,
                1,
                null,
                true);
    }
}
