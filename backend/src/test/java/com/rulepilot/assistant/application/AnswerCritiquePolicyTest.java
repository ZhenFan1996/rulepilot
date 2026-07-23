package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.LearningIntent;
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

class AnswerCritiquePolicyTest {

    private final UUID versionId = UUID.randomUUID();

    @Test
    void raises_critique_risk_for_live_follow_up_and_learning_questions_before_confidence() {
        StructuredRuleAnswer highConfidence = answer(AnswerConfidence.HIGH, List.of());
        QuestionContext ordinary = context(null, null);

        assertThat(AnswerCritiquePolicy.reviewRisk(ordinary, null, highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.STANDARD);
        assertThat(AnswerCritiquePolicy.reviewRisk(context("上一问", null), null, highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(context(null, LearningIntent.SIMPLIFY), null, highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(ordinary, UUID.randomUUID(), highConfidence))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
        assertThat(AnswerCritiquePolicy.reviewRisk(ordinary, null, answer(AnswerConfidence.LOW, List.of())))
                .isEqualTo(GeneratedContentCritic.ReviewRisk.LOW_CONFIDENCE);
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
                Set.of(),
                "行动");
        UUID runId = UUID.randomUUID();

        GeneratedContentCritic.ReviewRequest request = AnswerCritiquePolicy.request(
                runId, question, context("主要行动后还能执行自由行动吗？", LearningIntent.SIMPLIFY), answer, List.of(evidence));

        assertThat(request.assistantRunId()).isEqualTo(runId);
        assertThat(request.claims()).hasSize(2);
        assertThat(request.claims().getFirst().citationIds()).containsExactly(citation.chunkId());
        assertThat(request.claims().get(1).text()).isEqualTo("若规则另有例外，以例外为准。");
        assertThat(request.taskContext().objective()).contains("还能再做一次吗", "previous question for reference resolution only");
        assertThat(request.taskContext().requiredCoverage()).contains("LESSON_STEP_FOLLOW_UP", "SIMPLIFY", "repeatability claim");
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

    private QuestionContext context(String previousQuestion, LearningIntent learningIntent) {
        return new QuestionContext(
                versionId, "行动", "主要行动", 4, Set.of(), previousQuestion, learningIntent);
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
