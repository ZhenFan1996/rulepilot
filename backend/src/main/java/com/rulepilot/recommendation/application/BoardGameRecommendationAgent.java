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
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Public recommendation use-case boundary over the bounded ReAct runtime. */
@Service
@Profile("!test")
public class BoardGameRecommendationAgent {

    static final String SEARCH_TOOL = "search_bgg_catalog";
    static final String DISCOVER_TOOL = "discover_public_relationship";
    static final String RESEARCH_TOOL = "research_game_fit";
    static final String RECOMMEND_TOOL = "recommend_games";
    static final String COMPARE_TOOL = "compare_candidates";
    static final String PROMPT_VERSION = "recommendation-agent-v102-streamed-first-decision";

    private final RecommendationReActLoop loop;

    public BoardGameRecommendationAgent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json) {
        this(model, tools, selector, properties, json, ObservationRegistry.NOOP);
    }

    @Autowired
    public BoardGameRecommendationAgent(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json,
            ObservationRegistry observations) {
        loop = new RecommendationReActLoop(model, tools, selector, properties, json, observations);
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
        return converse(
                input,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                ignored -> {});
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<RecommendationPart> recommendationPartListener) {
        return loop.converse(
                input,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                recommendationPartListener);
    }

    ConversationRequest validatedConversationRequest(ConversationRequest input) {
        return loop.validate(input);
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        return loop.converseValidated(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener);
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        return loop.converseValidated(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                checkpointListener);
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        return conversePersisted(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                ignored -> {},
                checkpointListener);
    }

    ConversationResponse conversePersisted(
            ConversationRequest validatedRequestWithServerMemory,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<RecommendationPart> recommendationPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        return loop.converseValidated(
                validatedRequestWithServerMemory,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                recommendationPartListener,
                checkpointListener);
    }

    public record RecommendationPart(RecommendedGame game, List<ResearchSource> researchSources) {
        public RecommendationPart {
            Objects.requireNonNull(game, "streamed recommendation game is required");
            researchSources = researchSources == null ? List.of() : List.copyOf(researchSources);
        }
    }

    record TurnCheckpoint(RecommendationProfile profile, List<Game> verifiedGames) {
        TurnCheckpoint {
            profile = profile == null ? RecommendationProfile.empty() : profile;
            verifiedGames = verifiedGames == null ? List.of() : List.copyOf(verifiedGames);
        }
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
        OBSERVE_AND_DECIDE,
        REPLY_TO_USER,
        ASK_USER,
        SEARCH_BGG_CATALOG,
        DISCOVER_PUBLIC_RELATIONSHIP,
        RESEARCH_GAME_FIT,
        COMPARE_CANDIDATES,
        RECOMMEND_GAMES
    }

    /** Bounded public context for one validated action; it never contains raw tool JSON or internal identifiers. */
    public enum ProgressFocusKind {
        CATALOG_MECHANICS,
        CATALOG_CATEGORIES,
        CATALOG_FAMILIES,
        CATALOG_DESIGNERS,
        CATALOG_PUBLISHERS,
        CANDIDATE_TITLE_COUNT,
        VERIFIED_GAME_COUNT,
        RESEARCH_GAMES
    }

    public record ProgressFocus(ProgressFocusKind kind, List<String> values) {
        public ProgressFocus {
            Objects.requireNonNull(kind, "progress focus kind is required");
            Objects.requireNonNull(values, "progress focus values are required");
            values = values.stream()
                    .filter(Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
        }
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
            int sourceCount,
            ProgressFocus focus) {
        public ProgressUpdate(
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
            this(
                    stage,
                    phase,
                    action,
                    elapsedMs,
                    decisionCycle,
                    modelCalls,
                    actionCalls,
                    catalogCalls,
                    webResearchCalls,
                    observedCandidates,
                    verifiedCandidates,
                    hardRejectedCandidates,
                    sourceCount,
                    null);
        }

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

        public RecommendationProfile {
            type = type == null ? BggGameType.ALL : type;
            interaction = interaction == null ? InteractionPreference.ANY : interaction;
        }

        public static RecommendationProfile empty() {
            return new RecommendationProfile(
                    null,
                    null,
                    null,
                    BggGameType.ALL,
                    InteractionPreference.ANY);
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
            long totalElapsedMs,
            List<Long> modelCallElapsedMs,
            FailureReason failureReason,
            String failureDetailCode) {
        public HarnessTrace(
                int modelCalls,
                int catalogCalls,
                int webResearchCalls,
                boolean fallbackUsed,
                List<String> actions) {
            this(modelCalls, catalogCalls, webResearchCalls, fallbackUsed, actions, 0, List.of(), null, null);
        }

        public HarnessTrace(
                int modelCalls,
                int catalogCalls,
                int webResearchCalls,
                boolean fallbackUsed,
                List<String> actions,
                long totalElapsedMs) {
            this(modelCalls, catalogCalls, webResearchCalls, fallbackUsed, actions, totalElapsedMs, List.of(), null, null);
        }

        public HarnessTrace(
                int modelCalls,
                int catalogCalls,
                int webResearchCalls,
                boolean fallbackUsed,
                List<String> actions,
                long totalElapsedMs,
                List<Long> modelCallElapsedMs) {
            this(
                    modelCalls,
                    catalogCalls,
                    webResearchCalls,
                    fallbackUsed,
                    actions,
                    totalElapsedMs,
                    modelCallElapsedMs,
                    null,
                    null);
        }

        public HarnessTrace(
                int modelCalls,
                int catalogCalls,
                int webResearchCalls,
                boolean fallbackUsed,
                List<String> actions,
                long totalElapsedMs,
                List<Long> modelCallElapsedMs,
                FailureReason failureReason) {
            this(
                    modelCalls,
                    catalogCalls,
                    webResearchCalls,
                    fallbackUsed,
                    actions,
                    totalElapsedMs,
                    modelCallElapsedMs,
                    failureReason,
                    null);
        }

        public HarnessTrace {
            actions = List.copyOf(actions);
            // Existing persisted recommendation turns predate per-call timing evidence.
            modelCallElapsedMs = modelCallElapsedMs == null ? List.of() : List.copyOf(modelCallElapsedMs);
            if (totalElapsedMs < 0) throw new IllegalArgumentException("totalElapsedMs must not be negative");
            if (modelCallElapsedMs.size() > modelCalls
                    || modelCallElapsedMs.stream().anyMatch(elapsed -> elapsed == null || elapsed < 0)) {
                throw new IllegalArgumentException("model call elapsed times are invalid");
            }
            if (failureDetailCode != null
                    && !failureDetailCode.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("failure detail code is invalid");
            }
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
            List<CandidateClaim> claims,
            List<RecommendationReplyPart> replyParts) {
        public RecommendedGame {
            Objects.requireNonNull(game, "recommended game is required");
            claims = claims == null ? List.of() : List.copyOf(claims);
            replyParts = replyParts == null ? List.of() : List.copyOf(replyParts);
        }
    }

    public record RecommendationReplyPart(ReplyPartRole role, CandidateClaim claim) {
        public RecommendationReplyPart {
            Objects.requireNonNull(role, "recommendation reply role is required");
            Objects.requireNonNull(claim, "recommendation reply claim is required");
        }
    }

    public enum ReplyPartRole {
        WHY_FIT,
        VERIFIED_FACT,
        TRADEOFF
    }

    public record CandidateComparison(
            List<ComparisonCandidate> candidates,
            List<ComparisonAxis> axes) {
        public CandidateComparison {
            candidates = List.copyOf(candidates);
            axes = List.copyOf(axes);
            if (candidates.size() < 2 || axes.isEmpty()) {
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
            if (cells.size() < 2) {
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

    public enum Outcome {
        CONVERSATION,
        NEEDS_CLARIFICATION,
        RECOMMENDATIONS,
        NO_MATCH,
        UNAVAILABLE
    }

    /** Stable player-safe cause for a recommendation turn that reached no publishable terminal result. */
    public enum FailureReason {
        TIME_LIMIT,
        /** Read compatibility for turns completed before the cumulative recommendation run budget was removed. */
        RESOURCE_BUDGET_EXHAUSTED,
        MODEL_NOT_CONFIGURED,
        PROVIDER_CALL_FAILED,
        PROVIDER_PROTOCOL_INVALID,
        PROVIDER_OUTPUT_TRUNCATED,
        EMPTY_MODEL_RESPONSE,
        REPEATED_INCOMPATIBLE_ACTIONS,
        REPEATED_INVALID_ACTION,
        PUBLICATION_REJECTED,
        SERVICE_FAILURE
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
