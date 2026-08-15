package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;

/** Bounded, non-factual retrieval obligations accepted from the Answer Agent. */
public record AnswerQuestionPlan(
        List<Subquestion> subquestions,
        boolean agentPlanned,
        AnswerAid answerAid,
        ReferenceBinding referenceBinding,
        String boundReferenceQuestion,
        List<String> currentRuleObjectSpans,
        List<PageHint> pageHints) {

    public AnswerQuestionPlan {
        if (subquestions == null || subquestions.isEmpty() || subquestions.size() > 4) {
            throw new IllegalArgumentException("answer question plan is invalid");
        }
        subquestions = List.copyOf(subquestions);
        answerAid = answerAid == null ? AnswerAid.NONE : answerAid;
        referenceBinding = referenceBinding == null ? ReferenceBinding.CURRENT_QUESTION : referenceBinding;
        boundReferenceQuestion = boundReferenceQuestion == null || boundReferenceQuestion.isBlank()
                ? null
                : boundReferenceQuestion.strip();
        currentRuleObjectSpans = currentRuleObjectSpans == null
                ? List.of()
                : currentRuleObjectSpans.stream().map(String::strip).distinct().toList();
        pageHints = pageHints == null ? List.of() : pageHints.stream().distinct().toList();
        if (currentRuleObjectSpans.size() > 4
                || currentRuleObjectSpans.stream()
                        .anyMatch(value -> value.isBlank() || value.length() > 120)
                || pageHints.size() > 4) {
            throw new IllegalArgumentException("answer question focus is invalid");
        }
    }

    public AnswerQuestionPlan(
            List<Subquestion> subquestions,
            boolean agentPlanned,
            AnswerAid answerAid,
            ReferenceBinding referenceBinding) {
        this(subquestions, agentPlanned, answerAid, referenceBinding, null, List.of(), List.of());
    }

    public AnswerQuestionPlan(List<Subquestion> subquestions, boolean agentPlanned) {
        this(subquestions, agentPlanned, AnswerAid.NONE, ReferenceBinding.CURRENT_QUESTION);
    }

    static AnswerQuestionPlan fallback(UnderstoodQuestion question) {
        if (question == null) throw new IllegalArgumentException("understood question is required");
        return new AnswerQuestionPlan(
                List.of(new Subquestion(question.originalQuestion(), Set.of(EvidenceNeed.DIRECT_RULE))),
                false,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
    }

    Set<EvidenceNeed> evidenceNeeds() {
        return subquestions.stream()
                .flatMap(subquestion -> subquestion.evidenceNeeds().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Subquestion(String text, Set<EvidenceNeed> evidenceNeeds, QuestionOwner owner) {
        public Subquestion {
            if (text == null || text.isBlank() || text.length() > 300
                    || evidenceNeeds == null || evidenceNeeds.isEmpty() || evidenceNeeds.size() > 3
                    || owner == null) {
                throw new IllegalArgumentException("answer subquestion is invalid");
            }
            text = text.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
        }

        public Subquestion(String text, Set<EvidenceNeed> evidenceNeeds) {
            this(text, evidenceNeeds, QuestionOwner.CURRENT_QUESTION);
        }
    }

    public enum QuestionOwner {
        CURRENT_QUESTION,
        BOUND_REFERENCE
    }

    /** Validated locator copied from the current question; it never asserts that the page answers the question. */
    public record PageHint(String questionSpan, int pageNumber) {
        public PageHint {
            if (questionSpan == null || questionSpan.isBlank() || questionSpan.length() > 120
                    || pageNumber < 1 || pageNumber > 10_000) {
                throw new IllegalArgumentException("answer page hint is invalid");
            }
            questionSpan = questionSpan.strip();
        }
    }
}
