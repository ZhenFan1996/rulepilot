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
        ReferenceBinding referenceBinding) {

    public AnswerQuestionPlan {
        if (subquestions == null || subquestions.isEmpty() || subquestions.size() > 4) {
            throw new IllegalArgumentException("answer question plan is invalid");
        }
        subquestions = List.copyOf(subquestions);
        answerAid = answerAid == null ? AnswerAid.NONE : answerAid;
        referenceBinding = referenceBinding == null ? ReferenceBinding.CURRENT_QUESTION : referenceBinding;
    }

    public AnswerQuestionPlan(List<Subquestion> subquestions, boolean agentPlanned) {
        this(subquestions, agentPlanned, AnswerAid.NONE, ReferenceBinding.CURRENT_QUESTION);
    }

    static AnswerQuestionPlan fallback(UnderstoodQuestion question) {
        if (question == null) throw new IllegalArgumentException("understood question is required");
        return new AnswerQuestionPlan(
                List.of(new Subquestion(question.normalizedQuestion(), Set.of(EvidenceNeed.DIRECT_RULE))),
                false,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
    }

    Set<EvidenceNeed> evidenceNeeds() {
        return subquestions.stream()
                .flatMap(subquestion -> subquestion.evidenceNeeds().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Subquestion(String text, Set<EvidenceNeed> evidenceNeeds) {
        public Subquestion {
            if (text == null || text.isBlank() || text.length() > 300
                    || evidenceNeeds == null || evidenceNeeds.isEmpty() || evidenceNeeds.size() > 3) {
                throw new IllegalArgumentException("answer subquestion is invalid");
            }
            text = text.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
        }
    }
}
