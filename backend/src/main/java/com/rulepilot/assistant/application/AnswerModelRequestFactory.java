package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Set;

final class AnswerModelRequestFactory {

    ModelRequest create(
            UnderstoodQuestion question, QuestionContext context, List<HybridEvidenceHit> evidence) {
        return create(question, context, evidence, AnswerQuestionPlan.fallback(question));
    }

    ModelRequest create(
            UnderstoodQuestion question,
            QuestionContext context,
            List<HybridEvidenceHit> evidence,
            AnswerQuestionPlan questionPlan) {
        return new ModelRequest(
                question.originalQuestion(),
                question.type(),
                new AnswerContext(
                        questionPlan == null ? null : questionPlan.boundReferenceQuestion(),
                        context.learningIntent(),
                        context.outputLanguage(),
                        questionPlan == null
                                ? com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding.CURRENT_QUESTION
                                : questionPlan.referenceBinding(),
                        questionPlan == null ? List.of() : questionPlan.currentRuleObjectSpans(),
                        questionPlan == null
                                ? List.of()
                                : questionPlan.pageHints().stream()
                                        .map(AnswerQuestionPlan.PageHint::pageNumber)
                                        .toList()),
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(hit -> new EvidenceInput(
                                hit.chunkId(),
                                hit.sectionType(),
                                hit.heading(),
                                hit.excerpt(),
                                hit.pageFrom(),
                                hit.pageTo()))
                        .toList(),
                questionPlan == null ? Set.of() : questionPlan.evidenceNeeds(),
                questionPlan == null
                        ? com.rulepilot.assistant.RuleAnswerModel.AnswerAid.forLearningIntent(context.learningIntent())
                        : questionPlan.answerAid());
    }
}
