package com.rulepilot.retrieval;

import com.rulepilot.retrieval.AnswerRetrievalContext.LearningIntent;
import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalPlan.Subquestion;
import com.rulepilot.retrieval.AnswerRetrievalQuestion.QuestionType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds bounded retrieval queries from the accepted semantic question plan. */
public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_SECTION_FILTERS = 4;
    private static final int MAX_INTENTS = 5;

    private AnswerRetrievalPlanner() {}

    static List<RetrievalIntent> plan(AnswerRetrievalQuestion question, AnswerRetrievalContext context) {
        return plan(question, context, List.of(), AnswerRetrievalPlan.fallback(question));
    }

    static List<RetrievalIntent> plan(
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            List<String> rewrittenQueries) {
        return plan(question, context, rewrittenQueries, AnswerRetrievalPlan.fallback(question));
    }

    static List<RetrievalIntent> plan(
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            List<String> rewrittenQueries,
            AnswerRetrievalPlan questionPlan) {
        if (question == null || context == null) {
            throw new IllegalArgumentException("answer retrieval planning input is required");
        }
        AnswerRetrievalPlan acceptedPlan = questionPlan == null ? AnswerRetrievalPlan.fallback(question) : questionPlan;
        List<RetrievalIntent> intents = new ArrayList<>();
        for (Subquestion subquestion : acceptedPlan.subquestions()) {
            addDistinct(intents, new RetrievalIntent(
                    plannedQuery(subquestion), Set.of(), null, true, RetrievalPurpose.GENERAL));
            if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
        }
        if (rewrittenQueries != null) {
            for (String rewritten : rewrittenQueries) {
                String query = bounded(rewritten);
                if (!query.isBlank()) {
                    addDistinct(intents, new RetrievalIntent(query, Set.of(), null, false, RetrievalPurpose.GENERAL));
                }
                if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            }
        }
        if (acceptedPlan.evidenceNeeds().contains(EvidenceNeed.ADVICE)) {
            for (String cue : adviceSourceCueQueries()) {
                addDistinct(intents, new RetrievalIntent(
                        bounded(question.normalizedQuestion() + " " + cue),
                        Set.of(),
                        null,
                        false,
                        RetrievalPurpose.GENERAL));
                if (intents.size() == MAX_INTENTS) return List.copyOf(intents);
            }
        }
        String supplementary = supplementaryQuery(question, context, acceptedPlan);
        addDistinct(intents, new RetrievalIntent(
                supplementary, Set.of(), null, false, RetrievalPurpose.GENERAL));
        return intents.stream().limit(MAX_INTENTS).toList();
    }

    private static void addDistinct(List<RetrievalIntent> intents, RetrievalIntent candidate) {
        if (intents.stream().noneMatch(existing -> existing.query().equalsIgnoreCase(candidate.query()))) {
            intents.add(candidate);
        }
    }

    private static String plannedQuery(Subquestion subquestion) {
        StringBuilder query = new StringBuilder(subquestion.text());
        subquestion.evidenceNeeds().stream()
                .map(AnswerRetrievalPlanner::evidenceNeedFacets)
                .forEach(facet -> append(query, facet));
        return bounded(query.toString());
    }

    private static String evidenceNeedFacets(EvidenceNeed need) {
        return switch (need) {
            case DIRECT_RULE -> "direct rule clause";
            case CONDITION -> "condition prerequisite applicability";
            case SEQUENCE -> "order timing procedure";
            case EXCEPTION -> "exception restriction";
            case DEFINITION -> "definition glossary terminology";
            case RELATIONSHIP -> "rule relationship conflict precedence replacement";
            case VISUAL_REFERENCE -> "icon diagram label printed reference";
            case COMPLETE_LIST -> "complete enumerated list";
            case ADVICE -> "source-authored recommendation caution preferred choice";
            case PRIOR_TURN -> "follow-up dependency";
        };
    }

    public static List<String> adviceSourceCueQueries() {
        return List.of(
                "source-authored recommendation preferred choice ideal should recommendation advice",
                "source-authored caution avoid warning watch out");
    }

    private static String supplementaryQuery(
            AnswerRetrievalQuestion question, AnswerRetrievalContext context, AnswerRetrievalPlan plan) {
        StringBuilder query = new StringBuilder(question.normalizedQuestion());
        if (context.previousQuestion() != null) append(query, context.previousQuestion());
        if (!question.terms().isEmpty()) append(query, String.join(" ", question.terms()));
        append(query, questionTypeFacets(question.type()));
        append(query, learningFacets(context.learningIntent()));
        plan.evidenceNeeds().stream()
                .map(AnswerRetrievalPlanner::evidenceNeedFacets)
                .forEach(facet -> append(query, facet));
        return bounded(query.toString());
    }

    private static String learningFacets(LearningIntent intent) {
        if (intent == null) return null;
        return switch (intent) {
            case SIMPLIFY -> "core rule";
            case EXAMPLE -> "worked example setup action outcome";
            case DEFINE -> "definition terminology";
            case WHY -> "prerequisite consequence dependency";
            case EXCEPTIONS -> "restriction exception";
            case SOURCE -> "direct source clause";
            case VERIFY -> "direct rule condition exception";
        };
    }

    private static String questionTypeFacets(QuestionType type) {
        return switch (type) {
            case LESSON_STEP_FOLLOW_UP -> "step prerequisite consequence";
            case RULE_QUERY -> "rule condition consequence";
            case SITUATION_QUERY -> "applicability prerequisite consequence";
        };
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank() && target.indexOf(value) < 0) {
            target.append(' ').append(value.strip());
        }
    }

    static String bounded(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_QUERY_LENGTH).strip();
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
