package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidenceRefinementPolicyTest {

    private final UUID versionId = UUID.randomUUID();
    private final UnderstoodQuestion question = new UnderstoodQuestion(
            versionId,
            "任意自然语言问题",
            "任意自然语言问题",
            QuestionType.RULE_QUERY,
            List.of(),
            Set.of());
    private final QuestionContext context = new QuestionContext(versionId);

    @Test
    void refinesWhenDeterministicRetrievalFoundNoEvidence() {
        AnswerEvidenceRetriever.Result empty = new AnswerEvidenceRetriever.Result(
                List.of(), AnswerEvidenceRetriever.State.READY);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        question, context, directPlan(), empty))
                .isTrue();
    }

    @Test
    void keepsASingleCurrentDirectRulePlanOnTheZeroAdditionalCallPath() {
        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        question, context, directPlan(), ready()))
                .isFalse();
    }

    @Test
    void refinesAConcreteCalculationEvenWhenItsEvidenceNeedIsDirectRule() {
        AnswerQuestionPlan calculation = new AnswerQuestionPlan(
                List.of(subquestion(EvidenceNeed.DIRECT_RULE)),
                true,
                AnswerAid.CALCULATION,
                ReferenceBinding.CURRENT_QUESTION);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        question, context, calculation, ready()))
                .isTrue();
    }

    @Test
    void usesStructuredReferenceAndEvidenceObligationsInsteadOfQuestionKeywords() {
        AnswerQuestionPlan priorTurn = plan(
                List.of(subquestion(EvidenceNeed.DIRECT_RULE)),
                ReferenceBinding.PRIOR_GROUNDED_TURN);
        AnswerQuestionPlan completeList = plan(
                List.of(subquestion(EvidenceNeed.COMPLETE_LIST)),
                ReferenceBinding.CURRENT_QUESTION);
        AnswerQuestionPlan compound = plan(
                List.of(subquestion(EvidenceNeed.DIRECT_RULE), subquestion(EvidenceNeed.CONDITION)),
                ReferenceBinding.CURRENT_QUESTION);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, priorTurn, ready()))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, completeList, ready()))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, compound, ready()))
                .as("independent obligations still require exact-page coverage checks")
                .isTrue();
    }

    @Test
    void doesNotInventSemanticObligationsWhenStructuredPlanningFellBack() {
        AnswerQuestionPlan fallback = new AnswerQuestionPlan(
                List.of(subquestion(EvidenceNeed.DIRECT_RULE)),
                false,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
        UnderstoodQuestion keywordHeavy = new UnderstoodQuestion(
                versionId,
                "exceptions icon tie breaker priority complete list",
                "exceptions icon tie breaker priority complete list",
                QuestionType.RULE_QUERY,
                List.of(),
                Set.of());

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        keywordHeavy, context, fallback, ready()))
                .isFalse();
    }

    private AnswerQuestionPlan directPlan() {
        return plan(List.of(subquestion(EvidenceNeed.DIRECT_RULE)), ReferenceBinding.CURRENT_QUESTION);
    }

    private AnswerQuestionPlan plan(
            List<AnswerQuestionPlan.Subquestion> subquestions, ReferenceBinding binding) {
        return new AnswerQuestionPlan(subquestions, true, AnswerAid.NONE, binding);
    }

    private AnswerQuestionPlan.Subquestion subquestion(EvidenceNeed need) {
        return new AnswerQuestionPlan.Subquestion("source-bound subquestion", Set.of(need));
    }

    private AnswerEvidenceRetriever.Result ready() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "RULE", "Rule", "Direct evidence.", 2, 2, 0.8);
        return new AnswerEvidenceRetriever.Result(
                List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)),
                AnswerEvidenceRetriever.State.READY);
    }
}
