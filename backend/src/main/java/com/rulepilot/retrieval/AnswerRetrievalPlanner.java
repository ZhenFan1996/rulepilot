package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalPlan.Subquestion;
import java.util.ArrayList;
import java.util.List;

/** Builds bounded retrieval queries from the accepted semantic question plan. */
public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_INTENTS = 5;

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
            if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            for (String retrievalQuery : subquestion.retrievalQueries()) {
                addDistinct(intents, new RetrievalIntent(retrievalQuery, false));
                if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            }
        }
        for (String ruleObjectSpan : acceptedPlan.currentRuleObjectSpans()) {
            addDistinct(intents, new RetrievalIntent(ruleObjectSpan, true));
            if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
        }
        return intents.stream().limit(MAX_INTENTS).toList();
    }

    private static void addDistinct(List<RetrievalIntent> intents, RetrievalIntent candidate) {
        if (intents.stream().noneMatch(existing -> existing.query().equals(candidate.query()))) {
            intents.add(candidate);
        }
    }

    private static String currentFocusQuery(AnswerRetrievalQuestion question, AnswerRetrievalPlan plan) {
        if (question.currentQuestion().length() <= MAX_QUERY_LENGTH) {
            return question.currentQuestion();
        }
        return plan.subquestions().stream()
                .filter(subquestion -> subquestion.owner() == AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION)
                .map(Subquestion::text)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "a long question requires a structured current-question retrieval span"));
    }

    record RetrievalIntent(String query, boolean directQuestion) {
        public RetrievalIntent {
            if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("answer retrieval intent is invalid");
            }
            query = query.strip();
        }
    }
}
