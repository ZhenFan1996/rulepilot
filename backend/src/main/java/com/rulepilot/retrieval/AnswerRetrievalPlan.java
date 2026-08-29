package com.rulepilot.retrieval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Source obligations that retrieval may satisfy without receiving an assistant model contract. */
public record AnswerRetrievalPlan(
        List<Subquestion> subquestions,
        boolean calculationCoverageRequired,
        ReferenceBinding referenceBinding,
        String boundReferenceQuestion,
        List<String> currentRuleObjectSpans,
        List<PageHint> pageHints) {

    public AnswerRetrievalPlan {
        if (subquestions == null || subquestions.isEmpty()) {
            throw new IllegalArgumentException("answer retrieval plan is invalid");
        }
        subquestions = List.copyOf(subquestions);
        referenceBinding = referenceBinding == null ? ReferenceBinding.CURRENT_QUESTION : referenceBinding;
        boundReferenceQuestion = boundReferenceQuestion == null || boundReferenceQuestion.isBlank()
                ? null
                : boundReferenceQuestion.strip();
        currentRuleObjectSpans = currentRuleObjectSpans == null
                ? List.of()
                : currentRuleObjectSpans.stream().map(String::strip).distinct().toList();
        pageHints = pageHints == null ? List.of() : pageHints.stream().distinct().toList();
        if (currentRuleObjectSpans.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("answer retrieval focus is invalid");
        }
    }

    public AnswerRetrievalPlan(List<Subquestion> subquestions, boolean calculationCoverageRequired) {
        this(
                subquestions,
                calculationCoverageRequired,
                ReferenceBinding.CURRENT_QUESTION,
                null,
                List.of(),
                List.of());
    }

    static AnswerRetrievalPlan fallback(AnswerRetrievalQuestion question) {
        if (question == null) throw new IllegalArgumentException("answer retrieval question is required");
        return new AnswerRetrievalPlan(
                List.of(new Subquestion(question.currentQuestion(), Set.of(EvidenceNeed.DIRECT_RULE))),
                false);
    }

    public Set<EvidenceNeed> evidenceNeeds() {
        return subquestions.stream()
                .flatMap(subquestion -> subquestion.evidenceNeeds().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean visualRequested() {
        return evidenceNeeds().contains(EvidenceNeed.VISUAL_REFERENCE);
    }

    public boolean expandedCoverageRequired() {
        return calculationCoverageRequired
                || subquestions.size() > 1
                || evidenceNeeds().contains(EvidenceNeed.COMPLETE_LIST);
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
                throw new IllegalArgumentException("answer retrieval subquestion is invalid");
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

    public enum ReferenceBinding {
        CURRENT_QUESTION,
        PREVIOUS_QUESTION,
        PRIOR_GROUNDED_TURN
    }

    /** Scoped page locator from the current question; the locator itself carries no rule authority. */
    public record PageHint(String questionSpan, int pageNumber) {
        public PageHint {
            if (questionSpan == null || questionSpan.isBlank() || pageNumber < 1) {
                throw new IllegalArgumentException("answer retrieval page hint is invalid");
            }
            questionSpan = questionSpan.strip();
        }
    }

    public enum EvidenceNeed {
        DIRECT_RULE,
        CONDITION,
        SEQUENCE,
        EXCEPTION,
        DEFINITION,
        RELATIONSHIP,
        VISUAL_REFERENCE,
        COMPLETE_LIST,
        ADVICE,
        PRIOR_TURN
    }
}
