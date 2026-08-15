package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;

/** Decides whether the accepted structured plan warrants a bounded native-tool refinement pass. */
final class AnswerEvidenceRefinementPolicy {

    private AnswerEvidenceRefinementPolicy() {}

    static boolean requiresRefinement(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerEvidenceRetriever.Result deterministic) {
        return requiresRefinement(question, context, AnswerQuestionPlan.fallback(question), deterministic);
    }

    static boolean requiresRefinement(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerQuestionPlan plan,
            AnswerEvidenceRetriever.Result deterministic) {
        if (question == null || context == null || plan == null || deterministic == null
                || deterministic.state() != AnswerEvidenceRetriever.State.READY) {
            return false;
        }
        if (deterministic.evidence().isEmpty()) return true;
        if (!plan.agentPlanned()) return false;
        if (plan.referenceBinding() != ReferenceBinding.CURRENT_QUESTION) return true;
        if (plan.answerAid() == AnswerAid.CALCULATION) return true;
        return plan.subquestions().size() > 1
                || plan.evidenceNeeds().stream().anyMatch(need -> need != EvidenceNeed.DIRECT_RULE);
    }
}
