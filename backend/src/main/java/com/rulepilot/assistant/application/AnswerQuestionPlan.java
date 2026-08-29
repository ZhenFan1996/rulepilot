package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;

/** Non-factual retrieval obligations accepted from the Answer Agent. */
public record AnswerQuestionPlan(
        List<Subquestion> subquestions,
        boolean agentPlanned,
        AnswerAid answerAid,
        ReferenceBinding referenceBinding,
        String boundReferenceQuestion,
        List<String> currentRuleObjectSpans,
        List<PageHint> pageHints) {

    public AnswerQuestionPlan {
        if (subquestions == null || subquestions.isEmpty()) {
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
        if (currentRuleObjectSpans.stream().anyMatch(String::isBlank)) {
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
        return fallback(question, null);
    }

    static AnswerQuestionPlan fallback(UnderstoodQuestion question, LearningIntent learningIntent) {
        if (question == null) throw new IllegalArgumentException("understood question is required");
        return new AnswerQuestionPlan(
                List.of(new Subquestion(question.originalQuestion(), Set.of(EvidenceNeed.DIRECT_RULE))),
                false,
                AnswerAid.forLearningIntent(learningIntent),
                ReferenceBinding.CURRENT_QUESTION);
    }

    Set<EvidenceNeed> evidenceNeeds() {
        return subquestions.stream()
                .flatMap(subquestion -> subquestion.evidenceNeeds().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Subquestion(
            String text,
            Set<EvidenceNeed> evidenceNeeds,
            QuestionOwner owner,
            List<String> retrievalQueries) {
        public Subquestion {
            if (text == null || text.isBlank()
                    || evidenceNeeds == null || evidenceNeeds.isEmpty()
                    || owner == null || retrievalQueries == null
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank())) {
                throw new IllegalArgumentException("answer subquestion is invalid");
            }
            text = text.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
            retrievalQueries = retrievalQueries.stream().map(String::strip).distinct().toList();
        }

        public Subquestion(String text, Set<EvidenceNeed> evidenceNeeds, QuestionOwner owner) {
            this(text, evidenceNeeds, owner, List.of());
        }

        public Subquestion(String text, Set<EvidenceNeed> evidenceNeeds) {
            this(text, evidenceNeeds, QuestionOwner.CURRENT_QUESTION, List.of());
        }
    }

    public enum QuestionOwner {
        CURRENT_QUESTION,
        BOUND_REFERENCE
    }

    /** Validated locator copied from the current question; it never asserts that the page answers the question. */
    public record PageHint(String questionSpan, int pageNumber) {
        public PageHint {
            if (questionSpan == null || questionSpan.isBlank() || pageNumber < 1) {
                throw new IllegalArgumentException("answer page hint is invalid");
            }
            questionSpan = questionSpan.strip();
        }
    }
}
