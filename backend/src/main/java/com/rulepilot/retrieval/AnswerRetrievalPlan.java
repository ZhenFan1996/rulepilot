package com.rulepilot.retrieval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded source obligations that retrieval may satisfy without receiving an assistant model contract. */
public record AnswerRetrievalPlan(
        List<Subquestion> subquestions,
        boolean calculationCoverageRequired) {

    public AnswerRetrievalPlan {
        if (subquestions == null || subquestions.isEmpty() || subquestions.size() > 4) {
            throw new IllegalArgumentException("answer retrieval plan is invalid");
        }
        subquestions = List.copyOf(subquestions);
    }

    static AnswerRetrievalPlan fallback(AnswerRetrievalQuestion question) {
        if (question == null) throw new IllegalArgumentException("answer retrieval question is required");
        return new AnswerRetrievalPlan(
                List.of(new Subquestion(question.normalizedQuestion(), Set.of(EvidenceNeed.DIRECT_RULE))),
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

    public record Subquestion(String text, Set<EvidenceNeed> evidenceNeeds) {
        public Subquestion {
            if (text == null || text.isBlank() || text.length() > 300
                    || evidenceNeeds == null || evidenceNeeds.isEmpty() || evidenceNeeds.size() > 3) {
                throw new IllegalArgumentException("answer retrieval subquestion is invalid");
            }
            text = text.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
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
