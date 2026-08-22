package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerRetrievalContext;
import com.rulepilot.retrieval.AnswerRetrievalPlan;
import com.rulepilot.retrieval.AnswerRetrievalQuestion;

/** Projects accepted answer semantics into the retrieval module's stable, model-independent contract. */
final class AnswerRetrievalInputMapper {

    private AnswerRetrievalInputMapper() {}

    static AnswerRetrievalQuestion question(UnderstoodQuestion source) {
        if (source == null) throw new IllegalArgumentException("understood question is required");
        return new AnswerRetrievalQuestion(
                source.originalQuestion(),
                source.normalizedQuestion(),
                questionType(source.type()),
                source.terms());
    }

    static AnswerRetrievalContext context(QuestionContext source) {
        if (source == null) throw new IllegalArgumentException("question context is required");
        return new AnswerRetrievalContext(
                source.documentVersionId(),
                source.previousQuestion(),
                learningIntent(source.learningIntent()));
    }

    static AnswerRetrievalPlan plan(AnswerQuestionPlan source) {
        if (source == null) throw new IllegalArgumentException("answer question plan is required");
        return new AnswerRetrievalPlan(
                source.subquestions().stream()
                        .map(subquestion -> new AnswerRetrievalPlan.Subquestion(
                                subquestion.text(),
                                subquestion.evidenceNeeds().stream()
                                        .map(AnswerRetrievalInputMapper::evidenceNeed)
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                                questionOwner(subquestion.owner()),
                                subquestion.retrievalQueries()))
                        .toList(),
                source.answerAid() == AnswerAid.CALCULATION,
                referenceBinding(source.referenceBinding()),
                source.boundReferenceQuestion(),
                source.currentRuleObjectSpans(),
                source.pageHints().stream()
                        .map(hint -> new AnswerRetrievalPlan.PageHint(hint.questionSpan(), hint.pageNumber()))
                        .toList());
    }

    private static AnswerRetrievalQuestion.QuestionType questionType(QuestionType source) {
        return switch (source) {
            case LESSON_STEP_FOLLOW_UP -> AnswerRetrievalQuestion.QuestionType.LESSON_STEP_FOLLOW_UP;
            case RULE_QUERY -> AnswerRetrievalQuestion.QuestionType.RULE_QUERY;
            case SITUATION_QUERY -> AnswerRetrievalQuestion.QuestionType.SITUATION_QUERY;
        };
    }

    private static AnswerRetrievalContext.LearningIntent learningIntent(LearningIntent source) {
        if (source == null) return null;
        return switch (source) {
            case SIMPLIFY -> AnswerRetrievalContext.LearningIntent.SIMPLIFY;
            case EXAMPLE -> AnswerRetrievalContext.LearningIntent.EXAMPLE;
            case DEFINE -> AnswerRetrievalContext.LearningIntent.DEFINE;
            case WHY -> AnswerRetrievalContext.LearningIntent.WHY;
            case EXCEPTIONS -> AnswerRetrievalContext.LearningIntent.EXCEPTIONS;
            case SOURCE -> AnswerRetrievalContext.LearningIntent.SOURCE;
            case VERIFY -> AnswerRetrievalContext.LearningIntent.VERIFY;
        };
    }

    private static AnswerRetrievalPlan.EvidenceNeed evidenceNeed(EvidenceNeed source) {
        return switch (source) {
            case DIRECT_RULE -> AnswerRetrievalPlan.EvidenceNeed.DIRECT_RULE;
            case CONDITION -> AnswerRetrievalPlan.EvidenceNeed.CONDITION;
            case SEQUENCE -> AnswerRetrievalPlan.EvidenceNeed.SEQUENCE;
            case EXCEPTION -> AnswerRetrievalPlan.EvidenceNeed.EXCEPTION;
            case DEFINITION -> AnswerRetrievalPlan.EvidenceNeed.DEFINITION;
            case RELATIONSHIP -> AnswerRetrievalPlan.EvidenceNeed.RELATIONSHIP;
            case VISUAL_REFERENCE -> AnswerRetrievalPlan.EvidenceNeed.VISUAL_REFERENCE;
            case COMPLETE_LIST -> AnswerRetrievalPlan.EvidenceNeed.COMPLETE_LIST;
            case ADVICE -> AnswerRetrievalPlan.EvidenceNeed.ADVICE;
            case PRIOR_TURN -> AnswerRetrievalPlan.EvidenceNeed.PRIOR_TURN;
        };
    }

    private static AnswerRetrievalPlan.QuestionOwner questionOwner(AnswerQuestionPlan.QuestionOwner source) {
        return switch (source) {
            case CURRENT_QUESTION -> AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION;
            case BOUND_REFERENCE -> AnswerRetrievalPlan.QuestionOwner.BOUND_REFERENCE;
        };
    }

    private static AnswerRetrievalPlan.ReferenceBinding referenceBinding(
            com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding source) {
        return switch (source) {
            case CURRENT_QUESTION, NEEDS_CLARIFICATION -> AnswerRetrievalPlan.ReferenceBinding.CURRENT_QUESTION;
            case PREVIOUS_QUESTION -> AnswerRetrievalPlan.ReferenceBinding.PREVIOUS_QUESTION;
            case PRIOR_GROUNDED_TURN -> AnswerRetrievalPlan.ReferenceBinding.PRIOR_GROUNDED_TURN;
        };
    }
}
