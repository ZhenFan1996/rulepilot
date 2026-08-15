package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.PlannedPageHint;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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

        String groundingText = groundingText(deterministic, context, draft.referenceBinding());
        List<String> groundedTerms = groundedTerms(draft.terms(), groundingText);
        if (groundedTerms.size() != draft.terms().size()) return Optional.empty();
        List<String> currentRuleObjects = groundedCurrentRuleObjects(
                draft.ruleObjectSpans(), deterministic.originalQuestion());
        if (currentRuleObjects.size() != draft.ruleObjectSpans().size()) return Optional.empty();
        List<AnswerQuestionPlan.PageHint> pageHints = groundedPageHints(
                draft.pageHints(), deterministic.originalQuestion());
        if (pageHints.size() != draft.pageHints().size()) return Optional.empty();

        String resolvedQuestion = resolvedQuestion(deterministic, context, draft.referenceBinding());
        UnderstoodQuestion understood = new UnderstoodQuestion(
                deterministic.documentVersionId(),
                deterministic.originalQuestion(),
                normalize(resolvedQuestion),
                draft.questionType(),
                groundedTerms,
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
            if (!currentRuleObjects.isEmpty() || !pageHints.isEmpty()) return Optional.empty();
            return Optional.of(new Interpretation(understood, null, plannedLearningIntent));
        }
        String boundReferenceQuestion = boundReferenceQuestion(context, draft.referenceBinding());
        Optional<AnswerQuestionPlan> plan = groundedPlan(
                draft.subquestions(), deterministic.originalQuestion(), boundReferenceQuestion, currentRuleObjects);
        return plan.map(value -> new Interpretation(
                understood,
                new AnswerQuestionPlan(
                        value.subquestions(),
                        value.agentPlanned(),
                        plannedAid,
                        draft.referenceBinding(),
                        boundReferenceQuestion,
                        currentRuleObjects,
                        pageHints),
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

    private List<String> groundedTerms(List<String> terms, String groundingText) {
        String haystack = normalize(groundingText);
        Set<String> grounded = new LinkedHashSet<>();
        for (String term : terms) {
            String normalized = normalize(term);
            if (normalized.isBlank() || !haystack.contains(normalized)) continue;
            grounded.add(term.strip());
        }
        return List.copyOf(grounded);
    }

    private Optional<AnswerQuestionPlan> groundedPlan(
            List<PlannedSubquestion> proposed,
            String currentQuestion,
            String boundReferenceQuestion,
            List<String> currentRuleObjects) {
        String current = normalize(currentQuestion);
        String reference = normalize(boundReferenceQuestion);
        List<AnswerQuestionPlan.Subquestion> accepted = new java.util.ArrayList<>();
        for (PlannedSubquestion subquestion : proposed) {
            String span = normalize(subquestion.questionSpan());
            AnswerQuestionPlan.QuestionOwner owner;
            if (!span.isBlank() && current.contains(span)) {
                owner = AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION;
            } else if (!span.isBlank() && !reference.isBlank() && reference.contains(span)) {
                owner = AnswerQuestionPlan.QuestionOwner.BOUND_REFERENCE;
            } else {
                return Optional.empty();
            }
            AnswerQuestionPlan.Subquestion acceptedSubquestion = new AnswerQuestionPlan.Subquestion(
                    subquestion.questionSpan(), subquestion.evidenceNeeds(), owner);
            if (!accepted.contains(acceptedSubquestion)) accepted.add(acceptedSubquestion);
        }
        if (accepted.size() != proposed.size()) return Optional.empty();
        boolean coversCurrentTurn = accepted.stream()
                .anyMatch(subquestion -> subquestion.owner() == AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION);
        if (!coversCurrentTurn) return Optional.empty();
        boolean coversEveryCurrentObject = currentRuleObjects.stream()
                .map(this::normalize)
                .allMatch(object -> accepted.stream()
                        .filter(subquestion -> subquestion.owner() == AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION)
                        .map(AnswerQuestionPlan.Subquestion::text)
                        .map(this::normalize)
                        .anyMatch(span -> span.contains(object)));
        if (!coversEveryCurrentObject) return Optional.empty();
        List<AnswerQuestionPlan.Subquestion> currentFirst = accepted.stream()
                .sorted(java.util.Comparator.comparingInt(subquestion ->
                        subquestion.owner() == AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION ? 0 : 1))
                .toList();
        return Optional.of(new AnswerQuestionPlan(currentFirst, true));
    }

    private List<String> groundedCurrentRuleObjects(List<String> proposed, String currentQuestion) {
        String current = normalize(currentQuestion);
        return proposed.stream()
                .filter(value -> current.contains(normalize(value)))
                .map(String::strip)
                .distinct()
                .toList();
    }

    private List<AnswerQuestionPlan.PageHint> groundedPageHints(
            List<PlannedPageHint> proposed, String currentQuestion) {
        String current = normalize(currentQuestion);
        return proposed.stream()
                .filter(hint -> current.contains(normalize(hint.questionSpan())))
                .filter(hint -> containsStandaloneNumber(hint.questionSpan(), hint.pageNumber()))
                .map(hint -> new AnswerQuestionPlan.PageHint(hint.questionSpan(), hint.pageNumber()))
                .distinct()
                .toList();
    }

    private boolean containsStandaloneNumber(String value, int expected) {
        String digits = Integer.toString(expected);
        int from = 0;
        while (from < value.length()) {
            int index = value.indexOf(digits, from);
            if (index < 0) return false;
            int before = index - 1;
            int after = index + digits.length();
            boolean boundedBefore = before < 0 || !Character.isDigit(value.charAt(before));
            boolean boundedAfter = after >= value.length() || !Character.isDigit(value.charAt(after));
            if (boundedBefore && boundedAfter) return true;
            from = index + 1;
        }
        return false;
    }

    private String groundingText(
            UnderstoodQuestion deterministic, QuestionContext context, ReferenceBinding binding) {
        return switch (binding) {
            case CURRENT_QUESTION, NEEDS_CLARIFICATION -> deterministic.originalQuestion();
            case PREVIOUS_QUESTION -> context.previousQuestion() + " " + deterministic.originalQuestion();
            case PRIOR_GROUNDED_TURN -> context.priorTurnReference().question()
                    + " "
                    + deterministic.originalQuestion();
        };
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

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    record Interpretation(
            UnderstoodQuestion question,
            AnswerQuestionPlan plan,
            LearningIntent learningIntent) {}
}
