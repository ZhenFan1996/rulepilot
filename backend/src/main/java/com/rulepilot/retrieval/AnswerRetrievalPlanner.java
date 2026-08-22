package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalPlan.Subquestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds bounded retrieval queries from the accepted semantic question plan. */
public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_SECTION_FILTERS = 4;
    private static final int MAX_INTENTS = 5;

    private AnswerRetrievalPlanner() {}

    static List<RetrievalIntent> plan(AnswerRetrievalQuestion question, AnswerRetrievalContext context) {
        return plan(question, context, AnswerRetrievalPlan.fallback(question));
    }

    static List<RetrievalIntent> plan(
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            AnswerRetrievalPlan questionPlan) {
        if (question == null || context == null) {
            throw new IllegalArgumentException("answer retrieval planning input is required");
        }
        AnswerRetrievalPlan acceptedPlan = questionPlan == null ? AnswerRetrievalPlan.fallback(question) : questionPlan;
        List<RetrievalIntent> intents = new ArrayList<>();
        addDistinct(intents, new RetrievalIntent(
                currentFocusQuery(question, acceptedPlan), Set.of(), null, true, RetrievalPurpose.GENERAL));
        for (Subquestion subquestion : acceptedPlan.subquestions()) {
            addDistinct(intents, new RetrievalIntent(
                    subquestion.text(), Set.of(), null, true, RetrievalPurpose.GENERAL));
            if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            for (String retrievalQuery : subquestion.retrievalQueries()) {
                addDistinct(intents, new RetrievalIntent(
                        retrievalQuery, Set.of(), null, false, RetrievalPurpose.GENERAL));
                if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            }
        }
        for (String ruleObjectSpan : acceptedPlan.currentRuleObjectSpans()) {
            addDistinct(intents, new RetrievalIntent(
                    ruleObjectSpan, Set.of(), null, true, RetrievalPurpose.GENERAL));
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

    /** Historical purpose values remain readable in stored diagnostics; new plans use GENERAL. */
    enum RetrievalPurpose {
        GENERAL,
        ENDGAME_RESOLUTION,
        CONDITION_PROCEDURE
    }

    record RetrievalIntent(
            String query,
            Set<String> sectionTypes,
            String currentSectionType,
            boolean directQuestion,
            RetrievalPurpose purpose) {

        public RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType) {
            this(query, sectionTypes, currentSectionType, false, RetrievalPurpose.GENERAL);
        }

        public RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType, boolean directQuestion) {
            this(query, sectionTypes, currentSectionType, directQuestion, RetrievalPurpose.GENERAL);
        }

        public RetrievalIntent {
            if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                    || sectionTypes == null || sectionTypes.size() > MAX_SECTION_FILTERS || purpose == null) {
                throw new IllegalArgumentException("answer retrieval intent is invalid");
            }
            query = query.strip();
            sectionTypes = Set.copyOf(sectionTypes);
        }
    }
}
