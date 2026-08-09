package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
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
        if (deterministic == null || context == null || draft == null) return Optional.empty();
        if (!available(draft.referenceBinding(), context)) return Optional.empty();
        if (!consistentClarification(draft)) return Optional.empty();

        String groundingText = groundingText(deterministic, context, draft.referenceBinding());
        List<String> groundedTerms = groundedTerms(draft.terms(), groundingText);
        if (groundedTerms.size() != draft.terms().size()) return Optional.empty();

        String resolvedQuestion = resolvedQuestion(deterministic, context, draft.referenceBinding());
        return Optional.of(new UnderstoodQuestion(
                deterministic.documentVersionId(),
                deterministic.originalQuestion(),
                normalize(resolvedQuestion),
                draft.questionType(),
                groundedTerms,
                draft.missingContext()));
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
        return clarification == !draft.missingContext().isEmpty();
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

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
