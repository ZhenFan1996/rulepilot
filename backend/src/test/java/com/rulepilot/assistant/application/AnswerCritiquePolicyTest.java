package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerCritiquePolicyTest {

    private final UUID versionId = UUID.randomUUID();

    @Test
    void raises_critique_risk_for_live_follow_up_and_learning_questions_before_confidence() {
        StructuredRuleAnswer highConfidence = answer(AnswerConfidence.HIGH, List.of());
        QuestionContext ordinary = context(null, null);
        UnderstoodQuestion ordinaryQuestion = question(QuestionType.RULE_QUERY, "这个行动如何执行？");
        UnderstoodQuestion conditionalQuestion = question(
                QuestionType.LESSON_STEP_FOLLOW_UP, "如果母舰经过检查点，接下来要怎么处理？");

        assertThat(AnswerCritiquePolicy.reviewRisk(ordinaryQuestion, ordinary, highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.STANDARD);
        assertThat(AnswerCritiquePolicy.reviewRisk(ordinaryQuestion, context("上一问", null), highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(
                        ordinaryQuestion, context(null, LearningIntent.SIMPLIFY), highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(ordinaryQuestion, ordinary, answer(AnswerConfidence.LOW, List.of())))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.LOW_CONFIDENCE);
        assertThat(AnswerCritiquePolicy.reviewRisk(conditionalQuestion, ordinary, highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(
                        question(QuestionType.RULE_QUERY, "特殊规则和通用规则冲突时以哪个为准？"),
                        ordinary,
                        highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void allows_one_bounded_correction_for_each_high_impact_answer_context() {
        QuestionContext ordinary = context(null, null);
        UnderstoodQuestion ordinaryQuestion = question(QuestionType.RULE_QUERY, "这个行动如何执行？");
        UnderstoodQuestion conditionalQuestion = question(
                QuestionType.LESSON_STEP_FOLLOW_UP, "如果母舰经过检查点，接下来要怎么处理？");

        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(ordinaryQuestion, ordinary)).isFalse();
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(ordinaryQuestion, context("上一问", null)))
                .isTrue();
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(
                        ordinaryQuestion, context(null, LearningIntent.SIMPLIFY)))
                .isTrue();
        assertThat(AnswerCritiquePolicy.allowsBoundedCorrection(conditionalQuestion, ordinary)).isTrue();
    }

    @Test
    void builds_a_cited_critique_request_with_answer_and_exception_claims() {
        HybridEvidenceHit evidence = evidence("规则段落", "在主要行动后可以执行自由行动。", 8);
        RuleCitation citation = citation(evidence);
        StructuredRuleAnswer answer = answer(AnswerConfidence.HIGH, List.of(citation), List.of("若规则另有例外，以例外为准。"));
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "还能再做一次吗？",
                "还能再做一次吗",
                QuestionType.LESSON_STEP_FOLLOW_UP,
                List.of("再做一次"),
                Set.of());
        UUID runId = UUID.randomUUID();

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                runId, question, context("主要行动后还能执行自由行动吗？", LearningIntent.SIMPLIFY), answer, List.of(evidence));

        assertThat(request.assistantRunId()).isEqualTo(runId);
        assertThat(request.claims()).hasSize(2);
        assertThat(request.claims().getFirst().citationIds()).containsExactly(citation.chunkId());
        assertThat(request.claims().get(1).text()).isEqualTo("若规则另有例外，以例外为准。");
        assertThat(request.taskContext().objective()).contains("还能再做一次吗", "previous question for reference resolution only");
        assertThat(request.taskContext().requiredCoverage()).contains("LESSON_STEP_FOLLOW_UP", "SIMPLIFY", "repeatability claim");
        assertThat(request.taskContext().requiredCoverage()).contains(
                "assumed specific-over-general convention", "exact actor, object, action, condition, and timing");
        assertThat(request.evidence()).extracting(GeneratedContentCritic.Evidence::excerpt)
                .containsExactly("在主要行动后可以执行自由行动。");
    }

    @Test
    void preserves_critic_issue_order_when_constructing_one_bounded_revision_feedback_list() {
        GeneratedContentCritic.Review review = new GeneratedContentCritic.Review(true, List.of(
                new Issue(IssueType.CONTRADICTION, 2, List.of(UUID.randomUUID()), "行动顺序冲突。"),
                new Issue(IssueType.MISSING_EXCEPTION, 3, List.of(UUID.randomUUID()), "遗漏例外。")));

        assertThat(AnswerCritiquePolicy.revisionFeedback(review)).containsExactly(
                "CONTRADICTION: 行动顺序冲突。",
                "MISSING_EXCEPTION: 遗漏例外。");
    }

    @Test
    void sendsTheChosenFormulaToTheCriticForRuleSemanticReview() {
        HybridEvidenceHit evidence = evidence("计分", "每3个资源得5分。", 8);
        RuleCitation citation = citation(evidence);
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "共得10分。",
                "8个资源组成两组。",
                List.of(citation),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.GROUNDED_APPLICATION,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(new RuleCalculation("floor(8 / 3) * 5", "10")));

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question(QuestionType.SITUATION_QUERY, "我有8个资源，得多少分？"),
                context(null, null), answer, List.of(evidence));

        assertThat(request.claims()).extracting(GeneratedContentCritic.Claim::text)
                .contains("Derived calculation: floor(8 / 3) * 5 = 10");
        assertThat(request.taskContext().requiredCoverage()).contains("chosen formula must still match the rule");
    }

    @Test
    void sendsEachSituationRequirementAndPlayerFactToTheCriticWithItsOwnCitation() {
        HybridEvidenceHit evidence = evidence("行动条件", "结算前必须完成前置条件。", 8);
        RuleCitation citation = citation(evidence);
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, "可以结算。", "你说前置条件已经完成。",
                List.of(citation), List.of(), AnswerConfidence.HIGH, AnswerBasis.GROUNDED_APPLICATION,
                false, null, null, null, List.of(), List.of(),
                List.of(new RuleSituationCheck(
                        "The prerequisite must be complete.", SituationCheckStatus.CONFIRMED,
                        "我已经完成前置条件", List.of(citation.chunkId()))));

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question(QuestionType.SITUATION_QUERY, "我已经完成前置条件，现在可以结算吗？"),
                context(null, null), answer, List.of(evidence));

        assertThat(request.claims()).anySatisfy(claim -> {
            assertThat(claim.text()).contains("Situation requirement", "CONFIRMED", "我已经完成前置条件");
            assertThat(claim.citationIds()).containsExactly(citation.chunkId());
        });
        assertThat(request.taskContext().requiredCoverage())
                .contains("semantically confirms or contradicts", "NOT_PROVIDED");
    }

    @Test
    void sendsEachWalkthroughStepToTheCriticWithItsOrderingClaimAndOwnCitation() {
        HybridEvidenceHit evidence = evidence("结算顺序", "先支付费用，然后结算效果。", 8);
        RuleCitation citation = citation(evidence);
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, "先支付，再结算。", "按规则给出的顺序处理。",
                List.of(citation), List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(),
                List.of(new RuleWalkthroughStep(
                        "支付费用。", "在结算效果之前完成支付。", WalkthroughOrderBasis.RULE_ORDER,
                        List.of(citation.chunkId()))));

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question(QuestionType.RULE_QUERY, "这个效果的具体步骤是什么？"),
                context(null, null), answer, List.of(evidence));

        assertThat(request.claims()).anySatisfy(claim -> {
            assertThat(claim.text()).contains("Walkthrough step", "RULE_ORDER", "支付费用");
            assertThat(claim.citationIds()).containsExactly(citation.chunkId());
        });
        assertThat(request.taskContext().requiredCoverage())
                .contains("RULE_ORDER requires evidence", "EXPLANATION_ORDER");
        assertThat(AnswerCritiquePolicy.reviewRisk(
                question(QuestionType.RULE_QUERY, "这个效果的具体步骤是什么？"), context(null, null), answer))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void sendsEachDecisionBranchToTheCriticWithItsBasisAndOwnCitation() {
        HybridEvidenceHit evidence = evidence("平局奖励", "第一名平局时，各自获得第二名奖励。", 12);
        RuleCitation citation = citation(evidence);
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, "平局名次决定奖励。", "逐个条件核对。",
                List.of(citation), List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleDecisionBranch(
                        "Players tie for first place.", "Each receives the second reward.",
                        DecisionBranchBasis.EXPLICIT_RULE, List.of(citation.chunkId()))));

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question(QuestionType.RULE_QUERY, "不同名次平局时分别会怎样？"),
                context(null, null), answer, List.of(evidence));

        assertThat(request.claims()).anySatisfy(claim -> {
            assertThat(claim.text()).contains("Decision branch", "EXPLICIT_RULE", "tie for first place");
            assertThat(claim.citationIds()).containsExactly(citation.chunkId());
        });
        assertThat(request.taskContext().requiredCoverage())
                .contains("condition-to-outcome relationship", "RULEBOOK_EXAMPLE", "swapped outcomes");
        assertThat(AnswerCritiquePolicy.reviewRisk(
                question(QuestionType.RULE_QUERY, "不同名次平局时分别会怎样？"), context(null, null), answer))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void requiresTheCounterfactualConsequenceWhenThePlayerExplicitlyAsksForIt() {
        HybridEvidenceHit evidence = evidence("游戏结束", "满足条件后完成本轮。", 13);
        UnderstoodQuestion question = question(
                QuestionType.SITUATION_QUERY,
                "如果我可以结束游戏，其他玩家还会继续玩吗？");

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                UUID.randomUUID(), question, context(null, null), answer(AnswerConfidence.HIGH, List.of(citation(evidence))), List.of(evidence));

        assertThat(request.taskContext().requiredCoverage()).contains(
                "counterfactual follow-up", "immediate verdict is no", "named trigger is satisfied");
    }

    private QuestionContext context(String previousQuestion, LearningIntent learningIntent) {
        return new QuestionContext(versionId, previousQuestion, learningIntent, PlayerLocale.ZH_CN);
    }

    private UnderstoodQuestion question(QuestionType type, String normalizedQuestion) {
        return new UnderstoodQuestion(
                versionId,
                normalizedQuestion,
                normalizedQuestion,
                type,
                List.of("行动"),
                Set.of());
    }

    private StructuredRuleAnswer answer(AnswerConfidence confidence, List<RuleCitation> citations) {
        return answer(confidence, citations, List.of());
    }

    private StructuredRuleAnswer answer(
            AnswerConfidence confidence, List<RuleCitation> citations, List<String> exceptions) {
        List<RuleCitation> answerCitations = citations.isEmpty()
                ? List.of(new RuleCitation(
                        UUID.randomUUID(), versionId, "ACTIONS", "行动", "按规则行动。", 8, 8))
                : citations;
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "可以执行。",
                "按规则在主要行动后执行自由行动。",
                answerCitations,
                exceptions,
                confidence,
                false,
                null,
                null,
                null);
    }

    private HybridEvidenceHit evidence(String heading, String excerpt, int page) {
        return new HybridEvidenceHit(
                new RuleEvidenceHit(UUID.randomUUID(), versionId, "ACTIONS", heading, excerpt, page, page, 0.9),
                0.9,
                1,
                null,
                true);
    }

    private RuleCitation citation(HybridEvidenceHit evidence) {
        RuleEvidenceHit source = evidence.evidence();
        return new RuleCitation(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.heading(),
                source.excerpt(),
                source.pageFrom(),
                source.pageTo());
    }
}
