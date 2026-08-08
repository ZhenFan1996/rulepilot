package com.rulepilot.recommendation;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Model boundary for dialogue planning and candidate-aware recommendation composition. */
public interface BoardGameRecommendationAdvisor {

    Optional<Plan> plan(PlanningRequest request);

    Optional<Slate> compose(CompositionRequest request);

    record PlanningRequest(
            List<DialogueMessage> transcript,
            ProfileView currentProfile,
            Integer focusedBggId,
            String locale) {}

    record CompositionRequest(
            List<DialogueMessage> transcript,
            ProfileView profile,
            UserModel userModel,
            List<Candidate> candidates,
            BoardGameRecommendationWebResearch.Research research,
            Integer focusedBggId,
            String locale) {}

    record DialogueMessage(String role, String text) {}

    record ProfileView(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            BggGameType type,
            InteractionPreference interaction) {}

    record PreferencePatch(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            BggGameType type,
            InteractionPreference interaction) {}

    record Plan(
            DialogueAct act,
            PreferencePatch explicitPatch,
            UserModel userModel,
            String assistantMessage,
            String nextQuestion,
            boolean researchRequested,
            String researchQuestion,
            RetrievalPlan retrievalPlan) {
        public Plan(
                DialogueAct act,
                PreferencePatch explicitPatch,
                UserModel userModel,
                String assistantMessage,
                String nextQuestion,
                boolean researchRequested,
                String researchQuestion) {
            this(
                    act,
                    explicitPatch,
                    userModel,
                    assistantMessage,
                    nextQuestion,
                    researchRequested,
                    researchQuestion,
                    RetrievalPlan.empty());
        }

        public Plan {
            retrievalPlan = retrievalPlan == null ? RetrievalPlan.empty() : retrievalPlan;
        }
    }

    record RetrievalPlan(
            List<BggGameType> candidateTypes,
            List<FeatureConstraint> features,
            boolean candidateDiscoveryRequested) {
        public RetrievalPlan(List<BggGameType> candidateTypes, List<FeatureConstraint> features) {
            this(candidateTypes, features, false);
        }

        public RetrievalPlan {
            candidateTypes = candidateTypes == null ? List.of() : List.copyOf(candidateTypes);
            features = features == null ? List.of() : List.copyOf(features);
        }

        public static RetrievalPlan empty() {
            return new RetrievalPlan(List.of(), List.of(), false);
        }
    }

    record FeatureConstraint(String term, FeatureMode mode, FeatureSource source, String basedOn) {}

    record UserModel(String summary, List<PreferenceHypothesis> hypotheses) {}

    record PreferenceHypothesis(String text, Confidence confidence, String basedOn) {}

    record Candidate(
            int bggId,
            String name,
            Integer year,
            Integer rank,
            BigDecimal rating,
            BigDecimal weight,
            Integer minPlayers,
            Integer maxPlayers,
            Integer minutes,
            Integer minimumMinutes,
            Integer maximumMinutes,
            Integer minimumAge,
            Integer suggestedMinimumAge,
            String bestWith,
            String recommendedWith,
            Integer languageDependenceLevel,
            Integer weightVotes,
            List<String> categories,
            List<String> mechanics,
            List<String> families,
            List<String> designers,
            List<String> publishers) {}

    record Slate(String assistantMessage, String nextQuestion, List<Choice> choices) {}

    record Choice(
            int bggId,
            List<String> preferenceReasons,
            List<ResearchedReason> researchedReasons,
            List<String> tradeoffs) {}

    record ResearchedReason(String text, List<Integer> sourceIndexes) {}

    enum DialogueAct {
        ASK,
        RECOMMEND,
        EXPLAIN
    }

    enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }

    enum FeatureMode {
        REQUIRED,
        PREFERRED,
        AVOID
    }

    enum FeatureSource {
        BGG_METADATA,
        EXPERIENCE
    }
}
