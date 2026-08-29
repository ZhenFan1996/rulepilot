package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalPlan.Subquestion;
import java.util.ArrayList;
import java.util.List;

/** Builds retrieval queries from the complete accepted semantic question plan. */
public final class AnswerRetrievalPlanner {

    private AnswerRetrievalPlanner() {}

    static List<RetrievalIntent> plan(AnswerRetrievalQuestion question) {
        return plan(question, AnswerRetrievalPlan.fallback(question));
    }

    static List<RetrievalIntent> plan(AnswerRetrievalQuestion question, AnswerRetrievalPlan questionPlan) {
        if (question == null) throw new IllegalArgumentException("answer retrieval question is required");
        AnswerRetrievalPlan acceptedPlan = questionPlan == null ? AnswerRetrievalPlan.fallback(question) : questionPlan;
        List<RetrievalIntent> intents = new ArrayList<>();
        addDistinct(intents, new RetrievalIntent(currentFocusQuery(question, acceptedPlan), true));
        for (Subquestion subquestion : acceptedPlan.subquestions()) {
            addDistinct(intents, new RetrievalIntent(subquestion.text(), true));
            for (String retrievalQuery : subquestion.retrievalQueries()) {
                addDistinct(intents, new RetrievalIntent(retrievalQuery, false));
            }
        }
        for (String ruleObjectSpan : acceptedPlan.currentRuleObjectSpans()) {
            addDistinct(intents, new RetrievalIntent(ruleObjectSpan, true));
        }
        return List.copyOf(intents);
    }

    private static void addDistinct(List<RetrievalIntent> intents, RetrievalIntent candidate) {
        if (intents.stream().noneMatch(existing -> existing.query().equals(candidate.query()))) {
            intents.add(candidate);
        }
    }

    private static String currentFocusQuery(AnswerRetrievalQuestion question, AnswerRetrievalPlan plan) {
        return question.currentQuestion();
    }

    record RetrievalIntent(String query, boolean directQuestion) {
        public RetrievalIntent {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("answer retrieval intent is invalid");
            }
            query = query.strip();
        }
    }
}
