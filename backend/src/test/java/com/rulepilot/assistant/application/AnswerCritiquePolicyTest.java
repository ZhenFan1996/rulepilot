package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
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
    void letsDeterministicallyPublishedAnswersUseTheStandardRuntimePath() {
        StructuredRuleAnswer direct = copyWithRisk(
                answer(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE, List.of());

        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), direct))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.STANDARD);
    }

    @Test
    void retainsSemanticReviewForLowConfidenceAndDerivedApplications() {
        StructuredRuleAnswer direct = answer();
        StructuredRuleAnswer lowConfidence = copyWithRisk(
                direct, AnswerConfidence.LOW, AnswerBasis.DIRECT_RULE, List.of());
        StructuredRuleAnswer calculated = copyWithRisk(
                direct,
                AnswerConfidence.HIGH,
                AnswerBasis.GROUNDED_APPLICATION,
                List.of(new RuleCalculation("8 / 2", "4")));

        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), lowConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.LOW_CONFIDENCE);
        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), calculated))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void reviewsAMultiSourceConditionalCompleteListWithoutReviewingEveryMultiCitationAnswer() {
        UUID secondId = UUID.randomUUID();
        StructuredRuleAnswer multiSource = copyWithCitations(
                copyWithRisk(answer(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE, List.of()),
                List.of(
                        answer().citations().getFirst(),
                        new RuleCitation(
                                secondId,
                                versionId,
                                "EXCEPTION",
                                "Special case",
                                "The special case changes one listed route.",
                                4,
                                4)));
        RuleAnswerModel.ModelRequest conditionalList = modelRequest(Set.of(
                EvidenceNeed.DIRECT_RULE, EvidenceNeed.CONDITION, EvidenceNeed.COMPLETE_LIST));
        RuleAnswerModel.ModelRequest ordinary = modelRequest(Set.of(EvidenceNeed.DIRECT_RULE));

        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), conditionalList, multiSource))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(question(), context(), ordinary, multiSource))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.STANDARD);
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
                        "Direct verdict.",
                        "Grounded explanation.",
                        "Cited exception.",
                        "Calculation: 8 / 2 = 4",
                        "Walkthrough; orderBasis=RULE_ORDER; instruction=Pay first.; explanation=Then resolve.");
        assertThat(request.claims()).allSatisfy(claim ->
                assertThat(claim.citationIds()).containsExactly(chunkId));
        assertThat(request.evidence()).containsExactly(
                new GeneratedContentCritic.Evidence(chunkId, "Pay first, then resolve; eight divided by two is four."));
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

    private StructuredRuleAnswer copyWithRisk(
            StructuredRuleAnswer source,
            AnswerConfidence confidence,
            AnswerBasis basis,
            List<RuleCalculation> calculations) {
        return new StructuredRuleAnswer(
                source.documentVersionId(),
                source.status(),
                source.shortVerdict(),
                source.explanation(),
                source.citations(),
                source.exceptions(),
                confidence,
                basis,
                source.official(),
                source.confirmedRulingId(),
                source.confirmedRulingVersion(),
                source.clarification(),
                source.warnings(),
                calculations,
                source.situationChecks(),
                source.walkthroughSteps(),
                source.decisionBranches(),
                source.exceptionClauses(),
                source.termDefinitions(),
                source.workedExamples(),
                source.priorityResolutions(),
                source.timingResolutions(),
                source.tieResolutions(),
                source.scopeResolutions(),
                source.conceptComparisons(),
                source.ruleOptions());
    }

    private StructuredRuleAnswer copyWithCitations(
            StructuredRuleAnswer source, List<RuleCitation> citations) {
        return new StructuredRuleAnswer(
                source.documentVersionId(),
                source.status(),
                source.shortVerdict(),
                source.explanation(),
                citations,
                source.exceptions(),
                source.confidence(),
                source.answerBasis(),
                source.official(),
                source.confirmedRulingId(),
                source.confirmedRulingVersion(),
                source.clarification(),
                source.warnings(),
                source.calculations(),
                source.situationChecks(),
                source.walkthroughSteps(),
                source.decisionBranches(),
                source.exceptionClauses(),
                source.termDefinitions(),
                source.workedExamples(),
                source.priorityResolutions(),
                source.timingResolutions(),
                source.tieResolutions(),
                source.scopeResolutions(),
                source.conceptComparisons(),
                source.ruleOptions());
    }

    private RuleAnswerModel.ModelRequest modelRequest(Set<EvidenceNeed> needs) {
        return new RuleAnswerModel.ModelRequest(
                question().originalQuestion(),
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, PlayerLocale.EN),
                List.of(new RuleAnswerModel.EvidenceInput(
                        chunkId, "RULE", "Procedure", "Direct evidence.", 3, 3)),
                needs);
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
