package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;
import static com.rulepilot.recommendation.application.RecommendationReActLoop.MAX_REFERENCE_RESOLUTION_ATTEMPTS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ClarificationOption;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonAxis;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCandidate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCell;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.DiscoveryObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ResearchObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the eleven allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BoardGameRecommendationTools tools;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationProperties properties;
    private final ObjectMapper json;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationReActLoop runtime;

    RecommendationActions(
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json,
            RecommendationEvidenceReview evidenceReview,
            RecommendationReActLoop runtime) {
        this.tools = tools;
        this.selector = selector;
        this.properties = properties;
        this.json = json;
        this.evidenceReview = evidenceReview;
        this.runtime = runtime;
    }

    ActionOutcome execute(
            ToolCall call,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        try {
            JsonNode arguments = json.readTree(call.argumentsJson());
            return switch (call.name()) {
                case REPLY_TOOL -> reply(arguments, state, request, locale);
                case ASK_TOOL -> ask(arguments, state, request, locale);
                case RESOLVE_TOOL -> resolve(arguments, state, request, locale, progress);
                case SEARCH_TOOL -> search(arguments, state, request, progress);
                case BROWSE_TOOL -> browse(arguments, state, request, progress);
                case DISCOVER_TOOL -> discover(arguments, state, request, locale, progress);
                case LOOKUP_TOOL -> lookup(arguments, state, progress);
                case RESEARCH_TOOL -> research(arguments, state, locale, progress);
                case COMPARE_TOOL -> compare(arguments, state, request, locale);
                case NO_MATCH_TOOL -> noMatch(arguments, state, locale);
                case RECOMMEND_TOOL -> recommend(arguments, state, request, locale, progress);
                default -> rejected(state, "TOOL_NOT_ALLOWED", "Choose one action from the supplied action list.");
            };
        } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return ActionOutcome.terminal(runtime.unavailable(state, locale, "RUN_DEADLINE_EXCEEDED"));
        } catch (JsonProcessingException | InvalidAction exception) {
            InvalidAction invalid = exception instanceof InvalidAction value ? value : null;
            String code = invalid == null ? "INVALID_JSON" : invalid.code;
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejected(
                    state,
                    code,
                    invalid != null && invalid.guidance != null
                            ? invalid.guidance
                            : invalidActionGuidance(code));
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation action {} failed ({})", call.name(), exception.getClass().getSimpleName());
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejected(state, "ACTION_UNAVAILABLE", "The action failed. Choose another useful action or respond transparently.");
        }
    }

    private ActionOutcome reply(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("message"), Set.of("referencedBggIds", "preferenceUpdates"));
        evidenceReview.applyPreferenceUpdates(arguments, state, request);
        String message = playerFacingText(arguments.path("message"));
        List<Integer> referencedIds = arguments.has("referencedBggIds")
                ? ids(arguments.path("referencedBggIds"), 0, 5)
                : List.of();
        if (referencedIds.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("REPLY_ID_NOT_VERIFIED");
        }
        if (referencedIds.stream().anyMatch(id ->
                !state.comparisonSubjectIds.contains(id) && !state.targetGameIds.contains(id))) {
            throw new InvalidAction("REPLY_RECOMMENDATION_REQUIRES_CARDS");
        }
        state.actions.add("REPLY_TO_USER");
        return ActionOutcome.terminal(response(
                Outcome.CONVERSATION,
                message,
                state,
                locale,
                null,
                List.of()));
    }

    private ActionOutcome ask(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("question"), Set.of("options", "preferenceUpdates"));
        evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        String question = playerFacingText(arguments.path("question"));
        List<ClarificationOption> options = List.of();
        if (arguments.has("options")) {
            try {
                options = playerFacingStrings(arguments.path("options"), 2, 3).stream()
                        .map(option -> new ClarificationOption(option, option))
                        .toList();
            } catch (InvalidAction invalid) {
                state.actions.add("DROPPED_OPTIONAL_CLARIFICATION_OPTIONS:" + invalid.code);
            }
        }
        state.actions.add("ASK_USER");
        return ActionOutcome.terminal(response(
                Outcome.NEEDS_CLARIFICATION,
                question,
                state,
                locale,
                new Clarification(PreferenceField.CONVERSATION, question, options),
                List.of()));
    }

    private ActionOutcome compare(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(
                arguments,
                Set.of("candidateBggIds", "subjects", "preferredBggId"),
                Set.of("preferenceUpdates"));
        evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        List<Integer> candidateIds = ids(arguments.path("candidateBggIds"), 2, 5);
        List<Game> games = candidateIds.stream().map(state.verified::get).toList();
        if (games.stream().anyMatch(Objects::isNull)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_VERIFIED");
        }
        if (!state.comparisonSubjectIds.containsAll(candidateIds)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_IN_CONVERSATION");
        }
        List<String> subjects = playerFacingStrings(arguments.path("subjects"), 1, 3);
        Integer preferredBggId = preferredComparisonId(arguments.path("preferredBggId"));
        if (preferredBggId != null && !candidateIds.contains(preferredBggId)) {
            throw new InvalidAction("COMPARISON_PREFERENCE_INVALID");
        }

        List<ComparisonCandidate> candidates = games.stream()
                .map(game -> new ComparisonCandidate(
                        game,
                        selector.fitClaims(game, state.profile, runtime.chinese(locale))))
                .toList();
        List<ComparisonAxis> axes = subjects.stream()
                .map(subject -> new ComparisonAxis(
                        subject,
                        games.stream()
                                .map(game -> new ComparisonCell(
                                        game.ranking().bggId(),
                                        narrativeObservations(game, state.research).values().stream()
                                                .filter(observation -> observation.attribute().equals(subject))
                                                .findFirst()
                                                .orElse(null)))
                                .toList()))
                .toList();
        state.comparison = new CandidateComparison(candidates, axes);
        state.actions.add("COMPARE_CANDIDATES");
        return ActionOutcome.terminal(response(
                Outcome.CONVERSATION,
                comparisonMessage(games, preferredBggId, locale),
                state,
                locale,
                null,
                List.of()));
    }

    private Integer preferredComparisonId(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            int value = node.intValue();
            if (value > 0) return value;
        } else if (node.isTextual()) {
            try {
                int value = Integer.parseInt(node.asText());
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // Exact membership in candidateBggIds remains the authoritative identity boundary below.
            }
        }
        throw new InvalidAction("COMPARISON_PREFERENCE_INVALID");
    }

    private String comparisonMessage(List<Game> games, Integer preferredBggId, String locale) {
        if (preferredBggId == null) {
            return runtime.chinese(locale)
                    ? "这两款我不想硬分胜负：能核对的差异都在下面，真正的选择取决于你们更在意哪一边。"
                    : "I would not force a winner between these games: the checkable differences are below, and the choice depends on which side matters more to your group.";
        }
        Game preferred = games.stream()
                .filter(game -> game.ranking().bggId() == preferredBggId)
                .findFirst()
                .orElseThrow();
        String name = runtime.chinese(locale) && !preferred.details().officialChineseName().isBlank()
                ? preferred.details().officialChineseName()
                : preferred.ranking().sourceName();
        return runtime.chinese(locale)
                ? "真要替你们拍板，我会先选《" + name
                        + "》。我把能核对的差异放在下面；这是基于现有资料的取舍，不是来源替你们下的结论。"
                : "If I had to make the call for your group, I would start with “" + name
                        + ".” The checkable differences are below; this is my tradeoff based on the available evidence, not a conclusion made by the sources.";
    }

    private ActionOutcome noMatch(JsonNode arguments, RecommendationAgentState state, String locale) {
        requireObject(arguments, Set.of("relaxSubject"), Set.of());
        String subject = text(arguments.path("relaxSubject"), 1, 40);
        if (!runtime.relaxableSubjects(state).contains(subject)) {
            throw new InvalidAction("NO_MATCH_RELAXATION_NOT_ACTIONABLE");
        }
        String constraint = evidenceReview.constraintLabel(state.profile, subject, locale);
        String option = runtime.chinese(locale)
                ? "暂时取消“" + constraint + "”这条硬筛选，其他条件保持不变。"
                : "Temporarily remove the hard filter “" + constraint + "” and keep every other constraint unchanged.";
        String message = runtime.chinese(locale)
                ? "我核对了 " + state.verified.size() + " 款候选，目前没有一款同时满足全部硬条件。最小的可行调整是只取消“"
                        + constraint + "”这条硬筛选；我不会替你自动放宽。"
                : "I checked " + state.verified.size()
                        + " candidates, and none satisfies every hard constraint. The smallest actionable change is to remove only “"
                        + constraint + "”; I will not relax it without your confirmation.";
        state.actions.add("REPORT_NO_MATCH");
        Clarification clarification = new Clarification(
                PreferenceField.CONVERSATION,
                message,
                List.of(new ClarificationOption(option, option)));
        return ActionOutcome.terminal(response(
                Outcome.NO_MATCH,
                message,
                state,
                locale,
                clarification,
                List.of()));
    }

    private ActionOutcome resolve(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("title", "purpose"), Set.of("message", "preferenceUpdates"));
        String title = text(arguments.path("title"), 1, 160);
        if (!playerAuthoredTitle(request, title)) {
            throw new InvalidAction("REFERENCE_TITLE_NOT_GROUNDED");
        }
        NamedGamePurpose purpose = enumValue(
                NamedGamePurpose.class, arguments.path("purpose"), "NAMED_GAME_PURPOSE_INVALID");
        String groundedTitle = playerAuthoredReferenceTitle(request, title);
        String preferenceWarning = evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        state.referenceResolutionAttempts++;
        progress.accept(ProgressStage.READING_GAME_DETAILS);
        state.catalogCalls++;
        ReferenceObservation result = runtime.withinDeadline(state, () -> tools.resolveReferenceTitle(groundedTitle));
        state.actions.add("RESOLVE_BGG_REFERENCE");
        result.games().forEach(game -> {
            state.observeCandidate(game.ranking().bggId(), game.ranking().sourceName());
            if (game.details() != null) state.addVerified(game);
        });
        if (result.resolved()) {
            state.namedGamePurpose = purpose;
            result.games().stream()
                    .map(game -> game.ranking().bggId())
                    .forEach(id -> state.assignNamedGameRole(id, purpose));
            Game resolved = result.games().getFirst();
            int resolvedId = resolved.ranking().bggId();
            // An exact target is the player's choice, not a profile-ranked suggestion. A direct
            // exclusion remains authoritative, while stale recommendation preferences do not.
            if (purpose == NamedGamePurpose.TARGET_GAME
                    && !state.excludedIds.contains(resolvedId)) {
                String message = playerFacingText(arguments.path("message"));
                progress.accept(ProgressStage.COMPOSING_RESPONSE);
                state.actions.add("RECOMMEND_GAMES");
                List<RecommendedGame> games = selector.present(
                        List.of(resolved),
                        state.profile,
                        List.of(),
                        runtime.chinese(locale),
                        state.research);
                return ActionOutcome.terminal(response(
                        Outcome.RECOMMENDATIONS,
                        message,
                        state,
                        locale,
                        null,
                        games));
            }
        }
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.resolved() ? "SUCCESS" : result.status().name(),
                "code", result.code(),
                "purpose", purpose.name(),
                "preferenceUpdateWarning", preferenceWarning,
                "guidance", result.resolved()
                        ? switch (purpose) {
                            case COMPARISON_REFERENCE ->
                                "The player-named comparison reference is verified. Continue the still-open comparison request now: inspect your own distinct candidate hypotheses, then recommend from verified facts. Do not stop merely to confirm the title. Persist later explicit preference corrections only from their cited user-message evidence; never infer a preference from these game facts.";
                            case TARGET_GAME ->
                                "The player explicitly chose this verified game as the target. Finish with recommend_games so the application can render the verified, selectable target card. Do not inspect unrelated candidates or stop with plain text. Persist later explicit preference corrections only from cited user-message evidence; never infer a preference from these game facts.";
                            case DISCUSSION_SUBJECT, IDENTITY_ONLY ->
                                "Use only the observed BGG facts below. Continue the declared purpose, and persist any later explicit preference correction only from cited user-message evidence; never infer it from these game facts.";
                        }
                        : state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                                ? "This player-authored span did not resolve as a game title. If the request may instead describe a creator/person alias, award, list, or another external relationship, use public discovery when available rather than asking the player to supply the answer. Otherwise resolve a materially different player-authored title correction, ask for a genuinely missing identity detail, or respond transparently."
                                : "The bounded exact reference-resolution attempts did not uniquely resolve a title. Ask for the missing identity detail or respond transparently; do not invent another variant.",
                "resolvedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome search(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("titles"), Set.of("preferenceUpdates"));
        List<String> titles = strings(arguments.path("titles"), 1, 8, 2, 120);
        if (titles.stream().anyMatch(title -> playerAuthoredTitle(request, title))) {
            throw new InvalidAction("PLAYER_NAMED_TITLE_REQUIRES_RESOLUTION");
        }
        String preferenceWarning = evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        state.titleInspectionAttempted = true;
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        state.catalogCalls += 2;
        CatalogObservation result = runtime.withinDeadline(state, () -> tools.inspectTitles(titles));
        state.actions.add("SEARCH_BGG_BY_NAME");
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "preferenceUpdateWarning", preferenceWarning,
                "guidance", result.games().isEmpty()
                        ? "The one bounded title-inspection attempt returned no match and is now complete. Use public discovery when available, make one broad catalog browse, ask only if needed, or respond transparently; do not inspect titles again in this run."
                        : "Title identity and bounded BGG details are already verified. Do not look them up again; compare runMemory and finish when the slate is useful.",
                "verifiedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome browse(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of(), Set.of("types", "limit", "preferenceUpdates"));
        String preferenceWarning = evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        state.catalogBrowseAttempted = true;
        List<BggGameType> types = arguments.has("types")
                ? enumValues(BggGameType.class, arguments.path("types"), 0, 3, "GAME_TYPES_INVALID").stream()
                        .filter(value -> value != BggGameType.ALL)
                        .toList()
                : List.of();
        int limit = arguments.has("limit")
                ? integer(arguments.path("limit"), 1, MAX_VERIFIED_GAMES, "LIMIT_OUT_OF_RANGE")
                : Math.min(properties.modelCandidateLimit(), MAX_VERIFIED_GAMES);
        int eligibilityLimit = state.explicitRecommendationCount == null
                ? limit
                : Math.max(limit, state.explicitRecommendationCount);
        Set<Integer> unavailableCandidateIds = new LinkedHashSet<>(state.excludedIds);
        unavailableCandidateIds.addAll(state.previouslyShownIds);
        int requestedCandidateCount = Math.max(eligibilityLimit, properties.resultCount());
        int catalogLimit = Math.min(
                runtime.maximumRecommendationResults(),
                requestedCandidateCount
                        + Math.min(
                                Math.max(properties.resultCount(), unavailableCandidateIds.size()),
                                runtime.maximumRecommendationResults()));
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        state.catalogCalls++;
        CatalogObservation result = runtime.withinDeadline(
                state,
                () -> tools.searchCatalog(state.profile.type(), types, catalogLimit));
        state.actions.add("SEARCH_BGG_CATALOG");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        List<Game> eligible = result.succeeded()
                ? selector.eligible(result.games(), state.profile, unavailableCandidateIds, eligibilityLimit)
                : List.of();
        eligible.forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "preferenceUpdateWarning", preferenceWarning,
                "guidance", eligible.isEmpty()
                        ? "The one bounded catalog browse produced no hard-gate-eligible game and is now complete. Use a different available capability or finish transparently; do not browse again in this run."
                        : "These are broad catalog candidates and prove only their listed BGG observations. If any player-requested selection criterion is absent from those observations, public discovery remains available and must supply that evidence before recommendation. Otherwise compare the facts and finish; do not browse again.",
                "verifiedBggIds", eligible.stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("query"), Set.of("types", "preferenceUpdates"));
        String preferenceWarning = evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        state.discoveryAttempted = true;
        String query = text(arguments.path("query"), 3, 300);
        List<BggGameType> types = arguments.has("types")
                ? enumValues(BggGameType.class, arguments.path("types"), 0, 3, "GAME_TYPES_INVALID")
                : List.of();
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES);
        state.webResearchCalls++;
        DiscoveryObservation result = runtime.withinDeadline(
                state,
                () -> tools.discoverCandidates(
                        new BoardGameRecommendationWebResearch.DiscoveryRequest(query, types, locale)));
        state.actions.add("DISCOVER_CANDIDATES");
        CandidateDiscovery discovery = result.result().orElse(null);
        if (discovery == null) {
            if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.UNAVAILABLE) {
                state.disableWebResearch(result.code());
            }
            return ActionOutcome.observation(runtime.observation(Map.of(
                    "status", result.status().name(),
                    "code", result.code(),
                    "preferenceUpdateWarning", preferenceWarning,
                    "guidance", state.webResearchAvailable
                            ? "Public discovery returned no attributed candidates. Choose another retrieval action or respond transparently."
                            : "Public web research is unavailable for the rest of this run. Use the BGG title, lookup, or catalog actions, or finish transparently; do not retry web research.")));
        }
        List<String> titles = discovery.candidates().stream()
                .limit(6)
                .map(BoardGameRecommendationWebResearch.CandidateLead::name)
                .toList();
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        state.catalogCalls += 2;
        CatalogObservation inspection = runtime.withinDeadline(state, () -> tools.inspectTitles(titles));
        state.actions.add("SEARCH_BGG_BY_NAME");
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, inspection.sourceCount());
        inspection.games().forEach(state::addVerified);
        if (!inspection.games().isEmpty()) state.discoveryProducedVerifiedGames = true;
        state.research = mergeResearch(state.research, discoveryEvidence(discovery, inspection.games()));
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", inspection.succeeded() && !inspection.games().isEmpty() ? "SUCCESS" : "PARTIAL",
                "preferenceUpdateWarning", preferenceWarning,
                "guidance", inspection.games().isEmpty()
                        ? "Public search found source-backed title hypotheses, but none produced complete BGG details. Choose another retrieval action or respond transparently."
                        : "Public search supplied title hypotheses and the application already resolved and hydrated the matching BGG games. Do not search or look them up again; use the verified facts in runMemory.",
                "verifiedBggIds", inspection.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome lookup(JsonNode arguments, RecommendationAgentState state, Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("bggIds"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, MAX_VERIFIED_GAMES);
        if (!state.legalIds.containsAll(ids)) throw new InvalidAction("ID_NOT_OBSERVED");
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        state.catalogCalls++;
        CatalogObservation result = runtime.withinDeadline(state, () -> tools.lookupCandidates(ids));
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "guidance", result.games().isEmpty()
                        ? "No complete BGG details were returned. Try different observed candidates or respond transparently."
                        : "These bounded BGG facts are verified and may support comparison or final selection.",
                "verifiedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome research(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("bggIds", "question"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, 5);
        String question = text(arguments.path("question"), 1, 300);
        if (ids.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("GAME_NOT_VERIFIED");
        }
        state.researchAttempted = true;
        progress.accept(ProgressStage.RESEARCHING_GAME_FIT);
        state.webResearchCalls++;
        List<BoardGameRecommendationWebResearch.Candidate> candidates = ids.stream()
                .map(state.verified::get)
                .map(selector::researchCandidate)
                .toList();
        ResearchObservation result = runtime.withinDeadline(
                state,
                () -> tools.researchGameFit(candidates, locale, question));
        state.actions.add("RESEARCH_GAME_FIT");
        if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.UNAVAILABLE) {
            state.disableWebResearch(result.code());
        }
        Research added = result.result().orElse(Research.empty());
        state.research = mergeResearch(state.research, added);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.status().name(),
                "code", result.code(),
                "guidance", added.games().isEmpty()
                        ? state.webResearchAvailable
                                ? "No attributed experience evidence was returned. Do not invent it."
                                : "Public web research is unavailable for the rest of this run. Use verified BGG facts or finish transparently; do not retry web research."
                        : "Use these attributed observations as reported experience, distinct from BGG facts.",
                "researchedBggIds", added.games().stream().map(GameResearch::bggId).toList())));
    }

    private ActionOutcome recommend(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(
                arguments,
                Set.of("message", "selections"),
                Set.of("referenceBggIds", "preferenceUpdates", "shortfall"));
        evidenceReview.applyPreferenceUpdates(arguments, state, request);
        String proposedMessage = playerFacingText(arguments.path("message"));
        List<Integer> rawReferenceIds = arguments.has("referenceBggIds")
                ? ids(arguments.path("referenceBggIds"), 0, MAX_VERIFIED_GAMES)
                : List.of();
        if (rawReferenceIds.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("REFERENCE_ID_NOT_VERIFIED");
        }
        JsonNode selections = arguments.path("selections");
        if (!selections.isArray()) {
            throw new InvalidAction("SELECTIONS_ARRAY_REQUIRED");
        }
        int availableCount = runtime.recommendableIds(state).size();
        int expectedExplicitCount = state.explicitRecommendationCount == null
                ? -1
                : Math.min(state.explicitRecommendationCount, availableCount);
        if (selections.isEmpty()
                || selections.size() > state.maximumRecommendationResults
                || (state.explicitRecommendationCount != null
                        && selections.size() != expectedExplicitCount)) {
            throw new InvalidAction("SELECTION_COUNT_INVALID");
        }
        List<Game> selected = new ArrayList<>();
        Map<Integer, BoardGameRecommendationSelector.PreferenceLink> preferenceLinks = new LinkedHashMap<>();
        Map<Integer, BoardGameRecommendationSelector.CandidateNarrative> narratives = new LinkedHashMap<>();
        Map<String, String> userEvidence = evidenceReview.preferenceEvidence(request);
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode selection : selections) {
            requireObject(
                    selection,
                    Set.of("bggId"),
                    Set.of("preferenceLink", "why", "tradeoff", "internalEvidenceIds"));
            int id = integer(selection.path("bggId"), 1, Integer.MAX_VALUE, "BGG_ID_INVALID");
            if (!seen.add(id)) throw new InvalidAction("DUPLICATE_SELECTION");
            Game game = state.verified.get(id);
            if (game == null) throw new InvalidAction("FINAL_ID_NOT_VERIFIED");
            if (state.excludedIds.contains(id)) throw new InvalidAction("FINAL_ID_EXCLUDED");
            if (state.previouslyShownIds.contains(id) && !state.targetGameIds.contains(id)) {
                throw new InvalidAction("FINAL_ID_PREVIOUSLY_SHOWN");
            }
            if (state.comparisonReferenceIds.contains(id)) {
                throw new InvalidAction("FINAL_ID_IS_COMPARISON_REFERENCE");
            }
            if (!selector.eligible(game, state.profile)) throw new InvalidAction("FINAL_ID_FAILS_HARD_GATES");
            selected.add(game);
            try {
                BoardGameRecommendationSelector.CandidateNarrative narrative = validatedCandidateNarrative(
                        selection,
                        game,
                        state);
                if (narrative != null) narratives.put(id, narrative);
            } catch (InvalidAction invalid) {
                state.actions.add("DROPPED_OPTIONAL_CANDIDATE_NARRATIVE:" + invalid.code);
            } catch (IllegalArgumentException invalid) {
                state.actions.add("DROPPED_OPTIONAL_CANDIDATE_NARRATIVE:INVALID_OPTIONAL_NARRATIVE");
            }
            if (selection.has("preferenceLink")) {
                try {
                    preferenceLinks.put(
                            id,
                            validatedPreferenceLink(selection.path("preferenceLink"), game, userEvidence));
                } catch (InvalidAction invalid) {
                    state.actions.add("DROPPED_OPTIONAL_PREFERENCE_LINK:" + invalid.code);
                } catch (IllegalArgumentException invalid) {
                    state.actions.add("DROPPED_OPTIONAL_PREFERENCE_LINK:INVALID_OPTIONAL_LINK");
                }
            }
        }
        Set<Integer> selectedIds = selected.stream()
                .map(game -> game.ranking().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Integer> referenceIds = java.util.stream.Stream.concat(
                        state.comparisonReferenceIds.stream(), rawReferenceIds.stream())
                .distinct()
                .filter(id -> !selectedIds.contains(id))
                .limit(2)
                .toList();
        String message = proposedMessage;
        boolean hasShortfall = state.explicitRecommendationCount != null
                && availableCount < state.explicitRecommendationCount;
        List<ClarificationOption> shortfallOptions = validatedShortfallOptions(
                arguments,
                state,
                availableCount);
        RecommendationShortfall shortfall = hasShortfall
                ? new RecommendationShortfall(state.explicitRecommendationCount, availableCount)
                : null;
        Clarification clarification = shortfall == null || shortfallOptions.isEmpty()
                ? null
                : new Clarification(
                        PreferenceField.CONVERSATION,
                        message,
                        shortfallOptions);
        progress.accept(ProgressStage.COMPOSING_RESPONSE);
        if (shortfall != null) state.actions.add("RECOMMENDATION_AVAILABILITY_SHORTFALL");
        state.actions.add("RECOMMEND_GAMES");
        List<Game> references = referenceIds.stream().map(state.verified::get).toList();
        List<RecommendedGame> games = selector.present(
                selected,
                state.profile,
                references,
                runtime.chinese(locale),
                state.research,
                Map.copyOf(preferenceLinks),
                Map.copyOf(narratives));
        return ActionOutcome.terminal(response(
                Outcome.RECOMMENDATIONS,
                message,
                state,
                locale,
                clarification,
                games,
                shortfall));
    }

    private List<ClarificationOption> validatedShortfallOptions(
            JsonNode arguments,
            RecommendationAgentState state,
            int availableCount) {
        Integer requestedCount = state.explicitRecommendationCount;
        boolean expected = requestedCount != null && availableCount < requestedCount;
        if (!expected) {
            if (arguments.has("shortfall")) throw new InvalidAction("RECOMMENDATION_SHORTFALL_UNEXPECTED");
            return List.of();
        }
        if (!arguments.has("shortfall")) {
            throw new InvalidAction("RECOMMENDATION_SHORTFALL_REQUIRED");
        }
        JsonNode shortfall = arguments.path("shortfall");
        requireObject(
                shortfall,
                Set.of("requestedCount", "availableCount"),
                Set.of("relaxationOptions"));
        int proposedRequested = integer(
                shortfall.path("requestedCount"), 1, state.maximumRecommendationResults, "SHORTFALL_COUNT_INVALID");
        int proposedAvailable = integer(
                shortfall.path("availableCount"), 1, state.maximumRecommendationResults, "SHORTFALL_COUNT_INVALID");
        if (proposedRequested != requestedCount || proposedAvailable != availableCount) {
            throw new InvalidAction("SHORTFALL_COUNT_INVALID");
        }

        try {
            return validatedShortfallRelaxationOptions(shortfall, state);
        } catch (InvalidAction invalid) {
            state.actions.add("DROPPED_OPTIONAL_SHORTFALL_RELAXATION:" + invalid.code);
            return List.of();
        }
    }

    private List<ClarificationOption> validatedShortfallRelaxationOptions(
            JsonNode shortfall,
            RecommendationAgentState state) {
        List<String> allowedSubjects = runtime.shortfallRelaxableSubjects(state.profile);
        JsonNode options = shortfall.path("relaxationOptions");
        int minimumOptions = allowedSubjects.isEmpty() ? 0 : 1;
        int maximumOptions = Math.min(2, allowedSubjects.size());
        if (!options.isArray() || options.size() < minimumOptions || options.size() > maximumOptions) {
            throw new InvalidAction("SHORTFALL_RELAXATION_INVALID");
        }
        Set<String> subjects = new LinkedHashSet<>();
        List<ClarificationOption> optionsForPlayer = new ArrayList<>();
        for (JsonNode option : options) {
            requireObject(option, Set.of("subject", "reply"), Set.of());
            String subject = text(option.path("subject"), 1, 40);
            if (!allowedSubjects.contains(subject) || !subjects.add(subject)) {
                throw new InvalidAction("SHORTFALL_RELAXATION_INVALID");
            }
            String reply = playerFacingText(option.path("reply"));
            optionsForPlayer.add(new ClarificationOption(reply, reply));
        }
        return List.copyOf(optionsForPlayer);
    }

    private BoardGameRecommendationSelector.CandidateNarrative validatedCandidateNarrative(
            JsonNode selection,
            Game game,
            RecommendationAgentState state) {
        boolean hasNarrative = selection.has("why")
                || selection.has("tradeoff")
                || selection.has("internalEvidenceIds");
        if (!hasNarrative) return null;
        if (!selection.has("why") || !selection.has("internalEvidenceIds")) {
            throw new InvalidAction("CANDIDATE_NARRATIVE_INCOMPLETE");
        }
        String why = playerFacingText(selection.path("why"));
        String tradeoff = selection.has("tradeoff")
                ? playerFacingText(selection.path("tradeoff"))
                : "";
        Map<String, CandidateObservation> observations = narrativeObservations(game, state.research);
        List<String> internalEvidenceIds = strings(
                selection.path("internalEvidenceIds"),
                1,
                observations.size(),
                3,
                80);
        List<String> candidateEvidenceIds = internalEvidenceIds.stream()
                .filter(observations::containsKey)
                .toList();
        int droppedEvidenceIds = internalEvidenceIds.size() - candidateEvidenceIds.size();
        if (candidateEvidenceIds.isEmpty()) {
            throw new InvalidAction("CANDIDATE_NARRATIVE_EVIDENCE_WRONG_CANDIDATE");
        }
        if (droppedEvidenceIds > 0) {
            state.actions.add("DROPPED_OPTIONAL_CROSS_CANDIDATE_EVIDENCE_IDS:" + droppedEvidenceIds);
        }
        return new BoardGameRecommendationSelector.CandidateNarrative(
                why,
                tradeoff,
                candidateEvidenceIds.stream().map(observations::get).toList());
    }

    Map<String, CandidateObservation> narrativeObservations(Game game, Research research) {
        LinkedHashMap<String, CandidateObservation> observations = selector.observations(game).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> observation,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        observations.putAll(researchObservations(game.ranking().bggId(), research));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observations));
    }

    Map<String, CandidateObservation> researchObservations(int bggId, Research research) {
        LinkedHashMap<String, CandidateObservation> observations = new LinkedHashMap<>();
        if (research == null) return Map.of();
        research.games().stream()
                .filter(game -> game.bggId() == bggId)
                .findFirst()
                .ifPresent(game -> {
                    int ordinal = 0;
                    for (Observation observation : game.observations()) {
                        if (observation == null
                                || observation.text() == null
                                || observation.text().isBlank()
                                || observation.sourceIndexes().isEmpty()) {
                            continue;
                        }
                        String id = "R" + bggId + ":" + (++ordinal);
                        observations.put(id, new CandidateObservation(
                                id,
                                bggId,
                                CandidateObservation.Kind.ATTRIBUTED_REPORT,
                                "reportedExperience",
                                observation.text(),
                                observation.sourceIndexes()));
                    }
                });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observations));
    }

    private BoardGameRecommendationSelector.PreferenceLink validatedPreferenceLink(
            JsonNode node, Game game, Map<String, String> userEvidence) {
        requireObject(
                node,
                Set.of("evidenceId", "evidenceQuote", "taxonomyTerms"),
                Set.of());
        String evidenceId = text(node.path("evidenceId"), 1, 16);
        String evidence = userEvidence.get(evidenceId);
        if (evidence == null) throw new InvalidAction("PREFERENCE_LINK_EVIDENCE_NOT_GROUNDED");
        String quote = text(node.path("evidenceQuote"), 2, 120);
        if (!normalizedQuotedText(evidence).contains(normalizedQuotedText(quote))) {
            throw new InvalidAction("PREFERENCE_LINK_QUOTE_NOT_GROUNDED");
        }

        List<String> requestedTerms = strings(node.path("taxonomyTerms"), 1, 2, 1, 80);
        Map<String, String> verifiedTerms = java.util.stream.Stream.of(
                        game.details().mechanics(),
                        game.details().categories(),
                        game.details().families())
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        this::normalizedTitle,
                        value -> value,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<String> canonicalTerms = requestedTerms.stream()
                .map(term -> verifiedTerms.get(normalizedTitle(term)))
                .toList();
        if (canonicalTerms.stream().anyMatch(Objects::isNull)) {
            throw new InvalidAction("PREFERENCE_LINK_TAXONOMY_NOT_VERIFIED");
        }
        return new BoardGameRecommendationSelector.PreferenceLink(quote, canonicalTerms);
    }

    private String normalizedQuotedText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }

    private ActionOutcome rejected(RecommendationAgentState state, String code, String guidance) {
        state.actions.add("REJECTED_ACTION:" + code);
        return ActionOutcome.observation(runtime.error(code, guidance));
    }

    private String invalidActionGuidance(String code) {
        return switch (code) {
            case "REPLY_RECOMMENDATION_REQUIRES_CARDS" ->
                "New candidate recommendations must use recommend_games so the UI can render verified cards.";
            case "CANDIDATE_NARRATIVE_INCOMPLETE" ->
                "Each candidate narrative needs why plus one or more candidate-scoped internalEvidenceIds. tradeoff is optional.";
            case "SELECTIONS_ARRAY_REQUIRED" ->
                "selections must be a native JSON array of selection objects. Never quote or JSON-encode the array as a string.";
            case "RECOMMENDATION_SHORTFALL_REQUIRED" ->
                "Fewer hard-eligible candidates exist than the player requested. Return every available ID once and include the required shortfall object with the exact schema counts and one concrete allowed relaxation reply when offered.";
            case "RECOMMENDATION_SHORTFALL_UNEXPECTED" ->
                "Omit shortfall because the current hard-eligible pool can satisfy the requested count.";
            case "SHORTFALL_COUNT_INVALID" ->
                "Copy requestedCount and availableCount exactly from the current shortfall schema. Never pad or duplicate candidates.";
            case "SHORTFALL_RELAXATION_INVALID" ->
                "Use each allowed shortfall subject at most once and write one concrete direct player reply that relaxes only that bound.";
            case "CANDIDATE_NARRATIVE_EVIDENCE_WRONG_CANDIDATE" ->
                "Every candidate narrative evidenceId must come from that same selected game's observation map. Do not move evidence across candidates.";
            case "INVALID_JSON" ->
                "Return a fresh action with valid JSON arguments and escape string content correctly.";
            case "PREFERENCE_EVIDENCE_NOT_GROUNDED" ->
                "Use the exact evidenceId shown beside the user-authored message that states this hard constraint, or continue without changing the typed profile.";
            case "PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID" ->
                "Use evidenceClassification DIRECT for an explicitly stated constraint. Only an exact player count strongly implied by a fully described whole group may use CONTEXTUAL_COMPLETE_GROUP; otherwise omit the typed update.";
            case "PREFERENCE_LINK_EVIDENCE_NOT_GROUNDED" ->
                "Use an exact user-authored evidenceId from recentConversation, or omit preferenceLink when no qualitative preference is grounded.";
            case "PREFERENCE_LINK_QUOTE_NOT_GROUNDED" ->
                "evidenceQuote must be one short verbatim span from the cited user message. Do not paraphrase, translate, or invent a preference.";
            case "PREFERENCE_LINK_TAXONOMY_NOT_VERIFIED" ->
                "Every taxonomyTerms value must exactly match a mechanism, category, or family in this selected game's verified runMemory facts. Omit the link when there is no honest match.";
            case "REFERENCE_TITLE_NOT_GROUNDED" ->
                "Call resolve_bgg_game again with one complete, intact title span copied from a user-authored recentConversation turn. Do not remove a leading character, translate, expand, or guess the title.";
            case "PLAYER_NAMED_TITLE_REQUIRES_RESOLUTION" ->
                "inspect_candidate_titles is only for your own new recommendation hypotheses. Resolve the intact player-authored title first with resolve_bgg_game, then inspect separate candidate titles.";
            case "FINAL_ID_FAILS_HARD_GATES", "FINAL_ID_IS_COMPARISON_REFERENCE" ->
                "Select only IDs listed in runMemory.recommendableBggIds; those IDs already satisfy the current typed hard gates.";
            case "NO_MATCH_RELAXATION_NOT_ACTIONABLE" ->
                "Choose exactly one relaxSubject from the current report_no_match schema; it must unlock a verified candidate while every other hard constraint stays unchanged.";
            default -> "Correct the action arguments using the supplied JSON schema and current runMemory.";
        };
    }

    private ConversationResponse response(
            Outcome outcome,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games) {
        return response(outcome, message, state, locale, clarification, games, null);
    }

    private ConversationResponse response(
            Outcome outcome,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games,
            RecommendationShortfall shortfall) {
        ConversationResponse response = new ConversationResponse(
                outcome,
                DecisionMode.MODEL_ASSISTED,
                message,
                state.profile,
                clarification,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                runtime.responseSources(state, games),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        state.actions,
                        state.elapsedMs()),
                games,
                state.comparison,
                shortfall);
        runtime.logRun(response);
        return response;
    }

    Map<String, Object> gameObservation(Game game) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bggId", game.ranking().bggId());
        value.put("name", game.ranking().sourceName());
        putIfKnown(value, "year", game.ranking().publicationYear());
        if (game.details() == null) return value;
        var details = game.details();
        putIfText(value, "officialChineseName", details.officialChineseName());
        Map<String, List<String>> observations = selector.observations(game).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> List.of(observationKindCode(observation), observation.value()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        value.put("observations", observations);
        return value;
    }

    private String observationKindCode(CandidateObservation observation) {
        return switch (observation.kind()) {
            case STRUCTURED_METADATA -> "M";
            case TAXONOMY -> "T";
            case ATTRIBUTED_REPORT -> "A";
            case RULEBOOK_FACT -> "R";
        };
    }

    private void putIfKnown(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    List<Map<String, Object>> sourceObservations(List<Source> sources) {
        return sources.stream()
                .map(source -> Map.<String, Object>of(
                        "index", source.index(),
                        "title", source.title() == null ? "" : source.title(),
                        "domain", source.domain() == null ? "" : source.domain()))
                .toList();
    }

    private Research mergeResearch(Research current, Research added) {
        if (added == null || added.sources().isEmpty()) return current;
        List<Source> sources = new ArrayList<>(current.sources());
        Map<Integer, Integer> remapped = new LinkedHashMap<>();
        for (Source source : added.sources()) {
            if (sources.size() == 12) break;
            int index = sources.size() + 1;
            sources.add(new Source(index, source.title(), source.url(), source.domain()));
            remapped.put(source.index(), index);
        }
        Map<Integer, List<Observation>> observations = new LinkedHashMap<>();
        current.games().forEach(game -> observations
                .computeIfAbsent(game.bggId(), ignored -> new ArrayList<>())
                .addAll(game.observations()));
        added.games().forEach(game -> game.observations().stream()
                .filter(observation -> remapped.keySet().containsAll(observation.sourceIndexes()))
                .map(observation -> new Observation(
                        observation.text(),
                        observation.sourceIndexes().stream().map(remapped::get).toList()))
                .forEach(observation -> observations
                        .computeIfAbsent(game.bggId(), ignored -> new ArrayList<>())
                        .add(observation)));
        List<GameResearch> games = observations.entrySet().stream()
                .map(entry -> new GameResearch(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Research(games, List.copyOf(sources));
    }

    private Research discoveryEvidence(CandidateDiscovery discovery, List<Game> verifiedGames) {
        if (discovery == null || discovery.sources().isEmpty() || verifiedGames.isEmpty()) {
            return Research.empty();
        }
        List<Source> sources = discovery.sources().stream()
                .filter(this::credibleDiscoverySource)
                .toList();
        if (sources.isEmpty()) return Research.empty();
        Set<Integer> sourceIndexes = sources.stream()
                .map(Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Game> gamesByTitle = verifiedGames.stream()
                .collect(java.util.stream.Collectors.toMap(
                        game -> normalizedTitle(game.ranking().sourceName()),
                        game -> game,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<GameResearch> games = discovery.candidates().stream()
                .limit(6)
                .flatMap(candidate -> java.util.Optional.ofNullable(
                                gamesByTitle.get(normalizedTitle(candidate.name())))
                        .map(game -> new GameResearch(
                                game.ranking().bggId(),
                                List.of(new Observation(
                                        candidate.fitObservation(),
                                        candidate.sourceIndexes().stream()
                                                .filter(sourceIndexes::contains)
                                                .toList()))))
                        .stream())
                .filter(game -> game.observations().stream()
                        .anyMatch(observation -> !observation.text().isBlank()
                                && !observation.sourceIndexes().isEmpty()))
                .toList();
        return games.isEmpty() ? Research.empty() : new Research(games, sources);
    }

    private boolean credibleDiscoverySource(Source source) {
        String domain = source == null || source.domain() == null
                ? ""
                : source.domain().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        return !Set.of(
                        "amazon.com",
                        "facebook.com",
                        "instagram.com",
                        "pinterest.com",
                        "tiktok.com",
                        "x.com")
                .contains(domain);
    }

    private String normalizedTitle(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private void requireObject(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw new InvalidAction("ARGUMENT_OBJECT_REQUIRED");
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new InvalidAction("UNEXPECTED_ARGUMENT");
        if (required.stream().anyMatch(field -> !node.has(field))) {
            throw new InvalidAction("REQUIRED_ARGUMENT_MISSING");
        }
    }

    private String text(JsonNode node, int minimum, int maximum) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText().strip();
        if (value.length() < minimum || value.length() > maximum) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private String playerFacingText(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText();
        if (value.isBlank()) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private List<String> playerFacingStrings(JsonNode node, int minimumItems, int maximumItems) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(playerFacingText(value));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private List<String> strings(JsonNode node, int minimumItems, int maximumItems, int minimumLength, int maximumLength) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(text(value, minimumLength, maximumLength));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private List<Integer> ids(JsonNode node, int minimumItems, int maximumItems) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("ID_LIST_INVALID");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : node) values.add(integer(value, 1, Integer.MAX_VALUE, "BGG_ID_INVALID"));
        List<Integer> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private int integer(JsonNode node, int minimum, int maximum, String code) {
        if (!node.canConvertToInt()) throw new InvalidAction(code);
        int value = node.intValue();
        if (value < minimum || value > maximum) throw new InvalidAction(code);
        return value;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String code) {
        if (!node.isTextual()) throw new InvalidAction(code);
        try {
            String token = Normalizer.normalize(node.asText(), Normalizer.Form.NFKC)
                    .strip()
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                    .replaceAll("[-\\s]+", "_")
                    .toUpperCase(Locale.ROOT);
            return Enum.valueOf(type, token);
        } catch (IllegalArgumentException exception) {
            throw new InvalidAction(code);
        }
    }

    private <E extends Enum<E>> List<E> enumValues(
            Class<E> type, JsonNode node, int minimumItems, int maximumItems, String code) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction(code);
        }
        List<E> values = new ArrayList<>();
        for (JsonNode value : node) values.add(enumValue(type, value, code));
        List<E> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction(code);
        return distinct;
    }

    private boolean playerAuthoredTitle(ConversationRequest request, String title) {
        return request.transcript().stream()
                .filter(message -> "user".equals(message.role()))
                .map(DialogueMessage::text)
                .anyMatch(text -> BoardGameTitleGrounding.occursInPlayerText(text, title));
    }

    private String playerAuthoredReferenceTitle(ConversationRequest request, String title) {
        Optional<String> current = BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                request.message(), title);
        if (current.isPresent()) return current.orElseThrow();
        for (int index = request.transcript().size() - 1; index >= 0; index--) {
            DialogueMessage message = request.transcript().get(index);
            if (!"user".equals(message.role())) continue;
            Optional<String> expanded = BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                    message.text(), title);
            if (expanded.isPresent()) return expanded.orElseThrow();
        }
        return title;
    }

    record ActionOutcome(ConversationResponse response, String observation) {
        static ActionOutcome terminal(ConversationResponse response) {
            return new ActionOutcome(response, "");
        }

        static ActionOutcome observation(String observation) {
            return new ActionOutcome(null, observation);
        }
    }

    static final class InvalidAction extends RuntimeException {
        final String code;
        final String guidance;

        InvalidAction(String code) {
            this(code, null);
        }

        InvalidAction(String code, String guidance) {
            super(code);
            this.code = code;
            this.guidance = guidance;
        }
    }
}
