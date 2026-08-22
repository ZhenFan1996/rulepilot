package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Public recommendation use-case boundary over the bounded ReAct runtime. */
@Service
@Profile("!test")
public class BoardGameRecommendationAgent {

    static final String REPLY_TOOL = "reply_to_user";
    static final String ASK_TOOL = "ask_user";
    static final String RESOLVE_TOOL = "resolve_bgg_game";
    static final String SEARCH_TOOL = "inspect_candidate_titles";
    static final String BROWSE_TOOL = "browse_bgg_catalog";
    static final String DISCOVER_TOOL = "discover_public_candidates";
    static final String LOOKUP_TOOL = "lookup_bgg_games";
    static final String RESEARCH_TOOL = "research_game_fit";
    static final String COMPARE_TOOL = "compare_candidates";
    static final String NO_MATCH_TOOL = "report_no_match";
    static final String RECOMMEND_TOOL = "recommend_games";
    static final String PROMPT_VERSION = "recommendation-agent-v22-streamed-final-expression";

    private final RecommendationReActLoop loop;

    public BoardGameRecommendationAgent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json) {
        loop = new RecommendationReActLoop(model, tools, selector, properties, json);
    }

    @PreDestroy
    void stopBoundedCalls() {
        loop.stopBoundedCalls();
    }

    public ConversationResponse converse(ConversationRequest input, String requestedLocale) {
        return loop.converse(input, requestedLocale, null, ignored -> {});
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            Consumer<ProgressUpdate> progressListener) {
        return loop.converse(input, requestedLocale, null, progressListener);
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner) {
        return loop.converse(input, requestedLocale, modelConfigurationOwner, ignored -> {});
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        return loop.converse(input, requestedLocale, modelConfigurationOwner, progressListener);
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener) {
        return loop.converse(
                input,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener);
    }

    ConversationRequest validatedConversationRequest(ConversationRequest input) {
        return loop.validate(input);
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        return conversePersisted(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                ignored -> {});
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener) {
        return loop.converseValidated(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener);
    }

    public record ConversationRequest(
            RecommendationProfile profile,
            String message,
            List<Integer> excludedBggIds,
            List<DialogueMessage> transcript,
            Integer focusedBggId,
            List<KnownGame> knownGames,
            List<Integer> shownBggIds,
            List<Game> priorVerifiedGames) {
        public ConversationRequest(RecommendationProfile profile, String message) {
            this(profile, message, List.of(), List.of(), null, List.of(), List.of(), List.of());
        }

        public ConversationRequest(
                RecommendationProfile profile,
                String message,
                List<Integer> excludedBggIds,
                List<DialogueMessage> transcript,
                Integer focusedBggId,
                List<KnownGame> knownGames,
                List<Integer> shownBggIds) {
            this(profile, message, excludedBggIds, transcript, focusedBggId, knownGames, shownBggIds, List.of());
        }

        public ConversationRequest {
            excludedBggIds = excludedBggIds == null ? List.of() : List.copyOf(excludedBggIds);
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
            knownGames = knownGames == null ? List.of() : List.copyOf(knownGames);
            shownBggIds = shownBggIds == null ? List.of() : List.copyOf(shownBggIds);
            priorVerifiedGames = priorVerifiedGames == null ? List.of() : List.copyOf(priorVerifiedGames);
        }
    }

    public record DialogueMessage(String role, String text) {}

    public record KnownGame(int bggId, String name, String originalName) {}

    public enum ProgressStage {
        UNDERSTANDING_REQUEST,
        SELECTING_TOOLS,
        SEARCHING_BGG_CATALOG,
        READING_GAME_DETAILS,
        DISCOVERING_CANDIDATES,
        VERIFYING_BGG_CANDIDATES,
        RESEARCHING_GAME_FIT,
        COMPOSING_RESPONSE
    }

    public enum ProgressPhase {
        STARTED,
        COMPLETED,
        RETRYING,
        FAILED
    }

    /** Player-safe execution actions. These expose capability use, never prompts, parameters, or private reasoning. */
    public enum ProgressAction {
        UNDERSTAND_REQUEST,
        STREAM_NATURAL_REPLY,
        CHOOSE_NEXT_ACTION,
        REPLY_TO_USER,
        ASK_USER,
        RESOLVE_BGG_GAME,
        INSPECT_CANDIDATE_TITLES,
        BROWSE_BGG_CATALOG,
        DISCOVER_PUBLIC_CANDIDATES,
        LOOKUP_BGG_GAMES,
        RESEARCH_GAME_FIT,
        COMPARE_CANDIDATES,
        REPORT_NO_MATCH,
        RECOMMEND_GAMES
    }

    public record ProgressUpdate(
            ProgressStage stage,
            ProgressPhase phase,
            ProgressAction action,
            long elapsedMs,
            int decisionCycle,
            int modelCalls,
            int actionCalls,
            int catalogCalls,
            int webResearchCalls,
            int observedCandidates,
            int verifiedCandidates,
            int hardRejectedCandidates,
            int sourceCount) {
        public ProgressUpdate(ProgressStage stage, long elapsedMs) {
            this(stage, ProgressPhase.STARTED, null, elapsedMs, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public ProgressUpdate(
                ProgressStage stage,
                long elapsedMs,
                int observedCandidates,
                int verifiedCandidates,
                int hardRejectedCandidates,
                int sourceCount) {
            this(
                    stage,
                    ProgressPhase.STARTED,
                    null,
                    elapsedMs,
                    0,
                    0,
                    0,
                    0,
                    0,
                    observedCandidates,
                    verifiedCandidates,
                    hardRejectedCandidates,
                    sourceCount);
        }

        public ProgressUpdate {
            Objects.requireNonNull(stage, "progress stage is required");
            Objects.requireNonNull(phase, "progress phase is required");
            if (elapsedMs < 0
                    || decisionCycle < 0
                    || modelCalls < 0
                    || actionCalls < 0
                    || catalogCalls < 0
                    || webResearchCalls < 0
                    || observedCandidates < 0
                    || verifiedCandidates < 0
                    || hardRejectedCandidates < 0
                    || sourceCount < 0
                    || verifiedCandidates > observedCandidates
                    || hardRejectedCandidates > verifiedCandidates) {
                throw new IllegalArgumentException("recommendation progress is invalid");
            }
        }
    }

    public record RecommendationProfile(
            ConstraintRange<Integer> playerCount,
            ConstraintRange<Integer> durationMinutes,
            ConstraintRange<BigDecimal> complexity,
            BggGameType type,
            InteractionPreference interaction) {

        public RecommendationProfile(
                Integer players,
                Integer maxMinutes,
                BigDecimal maxWeight,
                BggGameType type,
                InteractionPreference interaction) {
            this(
                    players == null ? null : ConstraintRange.hardExact(players),
                    maxMinutes == null || maxMinutes == 0 ? null : ConstraintRange.hardAtMost(maxMinutes),
                    maxWeight == null || maxWeight.compareTo(BigDecimal.ZERO) == 0
                            ? null
                            : ConstraintRange.hardAtMost(maxWeight),
                    type,
                    interaction);
        }

        public RecommendationProfile {
            type = type == null ? BggGameType.ALL : type;
            interaction = interaction == null ? InteractionPreference.ANY : interaction;
        }

        public static RecommendationProfile empty() {
            return new RecommendationProfile(
                    (ConstraintRange<Integer>) null,
                    null,
                    null,
                    BggGameType.ALL,
                    InteractionPreference.ANY);
        }

        /** Legacy exact-player projection retained while clients migrate to the range contract. */
        public Integer players() {
            return playerCount != null && playerCount.exact() ? playerCount.minimum() : null;
        }

        public Integer minPlayers() {
            return playerCount == null ? null : playerCount.minimum();
        }

        public Integer maxPlayers() {
            return playerCount == null ? null : playerCount.maximum();
        }

        /** Legacy upper-duration projection retained while clients migrate to the range contract. */
        public Integer maxMinutes() {
            return durationMinutes == null ? null : durationMinutes.maximum();
        }

        public Integer minMinutes() {
            return durationMinutes == null ? null : durationMinutes.minimum();
        }

        /** Legacy upper-complexity projection retained while clients migrate to the range contract. */
        public BigDecimal maxWeight() {
            return complexity == null ? null : complexity.maximum();
        }

        public BigDecimal minWeight() {
            return complexity == null ? null : complexity.minimum();
        }
    }

    public record ConversationResponse(
            Outcome outcome,
            DecisionMode mode,
            String assistantMessage,
            RecommendationProfile profile,
            Clarification clarification,
            int sourceCount,
            int candidatesEvaluated,
            UserModelView userModel,
            List<ResearchSource> researchSources,
            HarnessTrace harness,
            List<RecommendedGame> games,
            CandidateComparison comparison,
            RecommendationShortfall shortfall) {
        public ConversationResponse(
                Outcome outcome,
                DecisionMode mode,
                String assistantMessage,
                RecommendationProfile profile,
                Clarification clarification,
                int sourceCount,
                int candidatesEvaluated,
                List<RecommendedGame> games) {
            this(
                    outcome,
                    mode,
                    assistantMessage,
                    profile,
                    clarification,
                    sourceCount,
                    candidatesEvaluated,
                    new UserModelView("", List.of()),
                    List.of(),
                    new HarnessTrace(0, 0, 0, false, List.of(), 0),
                    games,
                    null,
                    null);
        }

        public ConversationResponse(
                Outcome outcome,
                DecisionMode mode,
                String assistantMessage,
                RecommendationProfile profile,
                Clarification clarification,
                int sourceCount,
                int candidatesEvaluated,
                UserModelView userModel,
                List<ResearchSource> researchSources,
                HarnessTrace harness,
                List<RecommendedGame> games) {
            this(
                    outcome,
                    mode,
                    assistantMessage,
                    profile,
                    clarification,
                    sourceCount,
                    candidatesEvaluated,
                    userModel,
                    researchSources,
                    harness,
                    games,
                    null,
                    null);
        }

        public ConversationResponse(
                Outcome outcome,
                DecisionMode mode,
                String assistantMessage,
                RecommendationProfile profile,
                Clarification clarification,
                int sourceCount,
                int candidatesEvaluated,
                UserModelView userModel,
                List<ResearchSource> researchSources,
                HarnessTrace harness,
                List<RecommendedGame> games,
                CandidateComparison comparison) {
            this(
                    outcome,
                    mode,
                    assistantMessage,
                    profile,
                    clarification,
                    sourceCount,
                    candidatesEvaluated,
                    userModel,
                    researchSources,
                    harness,
                    games,
                    comparison,
                    null);
        }

        public ConversationResponse {
            researchSources = List.copyOf(researchSources);
            games = List.copyOf(games);
        }
    }

    public record UserModelView(String summary, List<PreferenceHypothesisView> hypotheses) {
        public UserModelView {
            hypotheses = List.copyOf(hypotheses);
        }
    }

    public record PreferenceHypothesisView(
            String field,
            String value,
            String text,
            String confidence,
            String basedOn) {}

    public record ResearchSource(int index, String title, String url, String domain) {}

    public record HarnessTrace(
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            List<String> actions,
            long totalElapsedMs) {
        public HarnessTrace(
                int modelCalls,
                int catalogCalls,
                int webResearchCalls,
                boolean fallbackUsed,
                List<String> actions) {
            this(modelCalls, catalogCalls, webResearchCalls, fallbackUsed, actions, 0);
        }

        public HarnessTrace {
            actions = List.copyOf(actions);
            if (totalElapsedMs < 0) throw new IllegalArgumentException("totalElapsedMs must not be negative");
        }
    }

    public record Clarification(PreferenceField field, String prompt, List<ClarificationOption> options) {
        public Clarification {
            options = List.copyOf(options);
        }
    }

    public record ClarificationOption(String value, String label) {}

    public record RecommendationShortfall(int requestedCount, int availableCount) {
        public RecommendationShortfall {
            if (requestedCount < 1 || availableCount < 1 || availableCount >= requestedCount) {
                throw new IllegalArgumentException("recommendation shortfall counts are invalid");
            }
        }
    }

    public record RecommendedGame(
            Game game,
            List<String> matches,
            List<String> tradeoffs,
            List<RecommendationReason> reasons,
            List<CandidateClaim> claims) {
        public RecommendedGame(Game game, List<String> matches, List<String> tradeoffs) {
            this(game, matches, tradeoffs, List.of(), List.of());
        }

        public RecommendedGame(
                Game game,
                List<String> matches,
                List<String> tradeoffs,
                List<RecommendationReason> reasons) {
            this(game, matches, tradeoffs, reasons, List.of());
        }

        public RecommendedGame {
            matches = List.copyOf(matches);
            tradeoffs = List.copyOf(tradeoffs);
            reasons = List.copyOf(reasons);
            claims = List.copyOf(claims);
        }
    }

    public record CandidateComparison(
            List<ComparisonCandidate> candidates,
            List<ComparisonAxis> axes) {
        public CandidateComparison {
            candidates = List.copyOf(candidates);
            axes = List.copyOf(axes);
            if (candidates.size() < 2 || candidates.size() > 5 || axes.isEmpty() || axes.size() > 3) {
                throw new IllegalArgumentException("candidate comparison shape is invalid");
            }
        }
    }

    public record ComparisonCandidate(Game game, List<CandidateClaim> fitClaims) {
        public ComparisonCandidate {
            Objects.requireNonNull(game, "comparison candidate game is required");
            fitClaims = List.copyOf(fitClaims);
        }
    }

    public record ComparisonAxis(String subject, List<ComparisonCell> cells) {
        public ComparisonAxis {
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("comparison subject is invalid");
            cells = List.copyOf(cells);
            if (cells.size() < 2 || cells.size() > 5) {
                throw new IllegalArgumentException("comparison cells are invalid");
            }
        }
    }

    public record ComparisonCell(int bggId, CandidateObservation observation) {
        public ComparisonCell {
            if (bggId <= 0) throw new IllegalArgumentException("comparison game id must be positive");
            if (observation != null && observation.bggId() != bggId) {
                throw new IllegalArgumentException("comparison observation belongs to another candidate");
            }
        }

        public boolean known() {
            return observation != null;
        }
    }

    public record RecommendationReason(ReasonKind kind, String text, List<Integer> sourceIndexes) {
        public RecommendationReason {
            sourceIndexes = List.copyOf(sourceIndexes);
        }
    }

    public enum ReasonKind {
        BGG_FACT,
        PREFERENCE_INFERENCE,
        WEB_RESEARCH
    }

    public enum Outcome {
        CONVERSATION,
        NEEDS_CLARIFICATION,
        RECOMMENDATIONS,
        NO_MATCH,
        UNAVAILABLE
    }

    public enum DecisionMode {
        MODEL_ASSISTED,
        MODEL_FAST_PATH
    }

    public enum PreferenceField {
        CONVERSATION
    }

    public enum InteractionPreference {
        ANY,
        COMPETITIVE,
        COOPERATIVE,
        TEAM
    }
}
