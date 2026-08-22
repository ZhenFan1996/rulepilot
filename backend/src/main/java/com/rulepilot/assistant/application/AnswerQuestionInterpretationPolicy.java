package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.PlannedPageHint;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.RuleAnswerModel.SubquestionOwner;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Optional;

/** Applies an untrusted semantic decision only through application-owned context and grounding invariants. */
final class AnswerQuestionInterpretationPolicy {

    Optional<UnderstoodQuestion> apply(
            UnderstoodQuestion deterministic,
            QuestionContext context,
            QuestionInterpretationDraft draft) {
        return applyWithPlan(deterministic, context, draft).map(Interpretation::question);
    }

    Optional<Interpretation> applyWithPlan(
            UnderstoodQuestion deterministic,
            QuestionContext context,
            QuestionInterpretationDraft draft) {
        if (deterministic == null || context == null || draft == null) return Optional.empty();
        if (!available(draft.referenceBinding(), context)) return Optional.empty();
        if (!consistentClarification(draft)) return Optional.empty();

        String resolvedQuestion = resolvedQuestion(deterministic, context, draft.referenceBinding());
        UnderstoodQuestion understood = new UnderstoodQuestion(
                deterministic.documentVersionId(),
                deterministic.originalQuestion(),
                resolvedQuestion.strip(),
                draft.questionType(),
                draft.terms(),
                draft.missingContext());
        LearningIntent plannedLearningIntent = context.learningIntent() == null
                ? draft.learningIntent()
                : context.learningIntent();
        AnswerAid plannedAid = context.learningIntent() == null
                ? draft.answerAid()
                : AnswerAid.forLearningIntent(context.learningIntent());
        AnswerAid intentAid = AnswerAid.forLearningIntent(draft.learningIntent());
        if (intentAid != AnswerAid.NONE && draft.answerAid() != intentAid) return Optional.empty();
        if (understood.needsClarification()) {
            if (!draft.ruleObjectSpans().isEmpty() || !draft.pageHints().isEmpty()) return Optional.empty();
            return Optional.of(new Interpretation(understood, null, plannedLearningIntent));
        }
        String boundReferenceQuestion = boundReferenceQuestion(context, draft.referenceBinding());
        Optional<AnswerQuestionPlan> plan = structuredPlan(draft.subquestions(), boundReferenceQuestion != null);
        return plan.map(value -> new Interpretation(
                understood,
                new AnswerQuestionPlan(
                        value.subquestions(),
                        value.agentPlanned(),
                        plannedAid,
                        draft.referenceBinding(),
                        boundReferenceQuestion,
                        draft.ruleObjectSpans(),
                        draft.pageHints().stream()
                                .map(hint -> new AnswerQuestionPlan.PageHint(
                                        hint.questionSpan(), hint.pageNumber()))
                                .toList()),
                plannedLearningIntent));
    }

    private boolean available(ReferenceBinding binding, QuestionContext context) {
        return switch (binding) {
            case CURRENT_QUESTION, NEEDS_CLARIFICATION -> true;
            case PREVIOUS_QUESTION -> context.previousQuestion() != null;
            case PRIOR_GROUNDED_TURN -> context.priorTurnReference() != null;
        };
    }

    private boolean consistentClarification(QuestionInterpretationDraft draft) {
        boolean clarification = draft.referenceBinding() == ReferenceBinding.NEEDS_CLARIFICATION;
        return clarification == !draft.missingContext().isEmpty()
                && clarification == draft.subquestions().isEmpty();
    }

    private Optional<AnswerQuestionPlan> structuredPlan(
            List<PlannedSubquestion> proposed,
            boolean boundReferenceAvailable) {
        List<AnswerQuestionPlan.Subquestion> accepted = new java.util.ArrayList<>();
        for (PlannedSubquestion subquestion : proposed) {
            if (subquestion.owner() == SubquestionOwner.BOUND_REFERENCE && !boundReferenceAvailable) {
                return Optional.empty();
            }
            AnswerQuestionPlan.QuestionOwner owner = subquestion.owner() == SubquestionOwner.CURRENT_QUESTION
                    ? AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION
                    : AnswerQuestionPlan.QuestionOwner.BOUND_REFERENCE;
            AnswerQuestionPlan.Subquestion acceptedSubquestion = new AnswerQuestionPlan.Subquestion(
                    subquestion.questionSpan(),
                    subquestion.evidenceNeeds(),
                    owner,
                    subquestion.retrievalQueries());
            if (!accepted.contains(acceptedSubquestion)) accepted.add(acceptedSubquestion);
        }
        if (accepted.size() != proposed.size()) return Optional.empty();
        boolean coversCurrentTurn = accepted.stream()
                .anyMatch(subquestion -> subquestion.owner() == AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION);
        if (!coversCurrentTurn) return Optional.empty();
        List<AnswerQuestionPlan.Subquestion> currentFirst = accepted.stream()
                .sorted(java.util.Comparator.comparingInt(subquestion ->
                        subquestion.owner() == AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION ? 0 : 1))
                .toList();
        return Optional.of(new AnswerQuestionPlan(currentFirst, true));
    }

    private String resolvedQuestion(
            UnderstoodQuestion deterministic, QuestionContext context, ReferenceBinding binding) {
        return switch (binding) {
            case CURRENT_QUESTION, NEEDS_CLARIFICATION -> deterministic.originalQuestion();
            case PREVIOUS_QUESTION -> context.previousQuestion() + "\nFollow-up: " + deterministic.originalQuestion();
            case PRIOR_GROUNDED_TURN -> context.priorTurnReference().question()
                    + "\nFollow-up: "
                    + deterministic.originalQuestion();
        };
    }

    private String boundReferenceQuestion(QuestionContext context, ReferenceBinding binding) {
        return switch (binding) {
            case CURRENT_QUESTION, NEEDS_CLARIFICATION -> null;
            case PREVIOUS_QUESTION -> context.previousQuestion();
            case PRIOR_GROUNDED_TURN -> context.priorTurnReference().question();
        };
    }

    record Interpretation(
            UnderstoodQuestion question,
            AnswerQuestionPlan plan,
            LearningIntent learningIntent) {}
}
