package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARISON_SUBJECTS;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PROMPT_VERSION;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_OBSERVED_CANDIDATES;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.RecommendationConversationText;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ResearchSource;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import java.math.BigDecimal;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the bounded observe-decide-act loop, budgets, and truthful degradation. */
final class RecommendationReActLoop {

    static final int MAX_MODEL_CALLS = 6;
    private static final int MAX_ACTION_CALLS = 6;
    private static final int MAX_OUTPUT_TOKENS = 900;
    static final int MAX_REFERENCE_RESOLUTION_ATTEMPTS = 2;
    private static final long RECENT_CANDIDATE_RESTORE_MILLIS = 2_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BoardGameRecommendationModel model;
    private final BoardGameRecommendationTools tools;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationProperties properties;
    private final ObjectMapper json;
    private final ExecutorService boundedCalls;
    private final long maximumRunMillis;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions actionExecutor;

    RecommendationReActLoop(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json) {
        this.model = model;
        this.tools = tools;
        this.selector = selector;
        this.properties = properties;
        this.json = json;
        boundedCalls = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recommendation-bounded-call-", 0).factory());
        maximumRunMillis = properties.timeout().toMillis();
        evidenceReview = new RecommendationEvidenceReview(json, this);
        actionExecutor = new RecommendationActions(tools, selector, properties, json, evidenceReview, this);
    }

    void stopBoundedCalls() {
        boundedCalls.shutdownNow();
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        long startedAt = System.nanoTime();
        Consumer<ProgressStage> progress = stage -> emitProgress(progressListener, stage, startedAt);
        progress.accept(ProgressStage.UNDERSTANDING_REQUEST);
        ConversationRequest request = validate(input);
        String locale = simplifiedChineseLocale(requestedLocale) ? "zh-CN" : "en";
        RecommendationAgentState state = new RecommendationAgentState(
                request,
                startedAt,
                modelConfigurationOwner,
                tools.webResearchConfigured(),
                maximumRecommendationResults());
        if (!model.configured(state.modelConfigurationOwner)) {
            return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
        }
        restoreRecentKnownCandidates(request, state, progress);

        List<String> preferenceEvidenceIds = evidenceReview.preferenceEvidence(request).keySet().stream().toList();
        List<ToolSpec> actions = actions(state.maximumRecommendationResults, preferenceEvidenceIds);

        List<Message> foundation = List.of(
                Message.system(systemPromptV2()),
                Message.user(agentInput(request, state, locale)));
        List<Message> messages = new ArrayList<>(foundation);
        Set<String> executed = new LinkedHashSet<>();

        while (state.modelCalls < MAX_MODEL_CALLS && state.actionCalls < MAX_ACTION_CALLS) {
            progress.accept(ProgressStage.SELECTING_TOOLS);
            state.modelCalls++;
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = availableActions(state, actions, preferenceEvidenceIds);
            try {
                List<Message> turnMessages = messages;
                turn = withinDeadline(
                        state,
                        () -> model.next(
                                new Request(turnMessages, currentActions, MAX_OUTPUT_TOKENS),
                                state.modelConfigurationOwner));
            } catch (RunDeadlineExceeded exception) {
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
                LOGGER.warn("Recommendation ReAct turn failed ({})", exception.getClass().getSimpleName());
                state.actions.add("MODEL_CALL_FAILED");
                return unavailable(state, locale, "MODEL_CALL_FAILED");
            }
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                state.actions.add("MODEL_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "MODEL_OUTPUT_TRUNCATED");
            }
            if (turn.toolCalls().size() != 1) {
                LOGGER.warn(
                        "Recommendation ReAct turn returned {} actions (textCharacters={})",
                        turn.toolCalls().size(),
                        turn.text().length());
                state.actions.add("INVALID_ACTION_COUNT");
                return unavailable(state, locale, "INVALID_ACTION_COUNT");
            }
            ToolCall call = turn.toolCalls().getFirst();
            state.actionCalls++;
            String fingerprint = call.name() + "\n" + call.argumentsJson();
            RecommendationActions.ActionOutcome outcome;
            if (currentActions.stream().noneMatch(action -> action.name().equals(call.name()))) {
                state.actions.add("REJECTED_UNAVAILABLE_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.observation(error(
                        "ACTION_NOT_AVAILABLE",
                        "That capability is not available in this turn. Choose one action from the supplied list."));
            } else if (!executed.add(fingerprint)) {
                state.actions.add("REJECTED_REPEATED_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.observation(error(
                        "REPEATED_ACTION",
                        "This exact action already ran. Use its observation and choose a materially different next action."));
            } else {
                outcome = actionExecutor.execute(call, state, request, locale, progress);
            }
            if (outcome.response() != null) return outcome.response();
            String observation = budgetedObservation(outcome.observation(), state);
            messages = new ArrayList<>(foundation);
            messages.add(Message.assistant(turn.text(), call));
            messages.add(Message.tool(call, observation));
        }
        state.actions.add("REACT_BUDGET_EXHAUSTED");
        return unavailable(state, locale, "BUDGET_EXHAUSTED");
    }

    ConversationResponse unavailable(RecommendationAgentState state, String locale, String code) {
        ConversationResponse fallback = verifiedCardFallback(state, locale, code);
        if (fallback != null) return fallback;
        state.actions.add("UNAVAILABLE:" + code);
        ConversationResponse response = new ConversationResponse(
                Outcome.UNAVAILABLE,
                DecisionMode.MODEL_ASSISTED,
                chinese(locale)
                        ? "这轮推荐没有完成；继续查找已经停止。你刚才的内容和已记录条件都还在，可以直接重试。"
                        : "This recommendation turn did not finish, and further searching has stopped. Your message and saved constraints are still here, so you can retry.",
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                state.research.sources().stream()
                        .map(source -> new ResearchSource(
                                source.index(), source.title(), source.url(), source.domain()))
                        .toList(),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        state.actions,
                        state.elapsedMs()),
                List.of(),
                null);
        logRun(response);
        return response;
    }

    private ConversationResponse verifiedCardFallback(RecommendationAgentState state, String locale, String code) {
        List<Game> candidates = recommendableIds(state).stream()
                .map(state.verified::get)
                .toList();
        if (candidates.isEmpty()) {
            candidates = state.verified.values().stream()
                    .filter(game -> !state.excludedIds.contains(game.ranking().bggId()))
                    .filter(game -> !state.comparisonReferenceIds.contains(game.ranking().bggId()))
                    .filter(game -> selector.eligible(game, state.profile))
                    .toList();
        }
        List<Game> selected = candidates.stream().limit(state.maximumRecommendationResults).toList();
        if (selected.isEmpty()) return null;

        Set<Integer> selectedIds = selected.stream()
                .map(game -> game.ranking().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Game> references = state.comparisonReferenceIds.stream()
                .filter(id -> !selectedIds.contains(id))
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
        List<RecommendedGame> games = selector.present(
                selected, state.profile, references, chinese(locale), state.research);
        state.actions.add("FALLBACK_VERIFIED_CARDS:" + code);
        String message = chinese(locale)
                ? "这轮深入比较没有顺利完成；我先保留已经核对过的候选卡。卡片只显示确认过的 BGG 事实，不会把未核实的桌感写成结论。你可以直接点名两款继续比较。"
                : "The deeper comparison did not finish cleanly, so I kept the candidates whose BGG facts were already verified. The cards do not turn unverified table feel into a claim; name two games to continue the comparison.";
        ConversationResponse response = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                message,
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                responseSources(state, games),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        true,
                        state.actions,
                        state.elapsedMs()),
                games,
                null);
        logRun(response);
        return response;
    }

    void logRun(ConversationResponse response) {
        LOGGER.info(
                "Recommendation ReAct run completed: promptVersion={}, outcome={}, totalElapsedMs={}, modelCalls={}, catalogCalls={}, webResearchCalls={}, candidatesEvaluated={}, actions={}",
                PROMPT_VERSION,
                response.outcome(),
                response.harness().totalElapsedMs(),
                response.harness().modelCalls(),
                response.harness().catalogCalls(),
                response.harness().webResearchCalls(),
                response.candidatesEvaluated(),
                response.harness().actions());
    }

    List<ResearchSource> responseSources(RecommendationAgentState state, List<RecommendedGame> games) {
        Set<Integer> cited = games.stream()
                .flatMap(game -> game.reasons().stream())
                .flatMap(reason -> reason.sourceIndexes().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (cited.isEmpty()) return List.of();
        return state.research.sources().stream()
                .filter(source -> cited.contains(source.index()))
                .map(source -> new ResearchSource(
                        source.index(), source.title(), source.url(), source.domain()))
                .toList();
    }

    private void restoreRecentKnownCandidates(
            ConversationRequest request,
            RecommendationAgentState state,
            Consumer<ProgressStage> progress) {
        if (request.knownGames().isEmpty() || request.shownBggIds().isEmpty()) return;

        Set<Integer> shown = new LinkedHashSet<>(request.shownBggIds());
        LinkedHashSet<Integer> recentIds = new LinkedHashSet<>();
        Integer focused = request.focusedBggId();
        if (focused != null && shown.contains(focused) && !state.excludedIds.contains(focused)) {
            recentIds.add(focused);
        }
        for (KnownGame known : request.knownGames()) {
            if (recentIds.size() >= properties.resultCount()) break;
            if (shown.contains(known.bggId()) && !state.excludedIds.contains(known.bggId())) {
                recentIds.add(known.bggId());
            }
        }
        if (recentIds.isEmpty()) return;

        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        state.catalogCalls++;
        Optional<CatalogObservation> restored = withinSoftDeadline(
                state,
                Math.min(RECENT_CANDIDATE_RESTORE_MILLIS, Math.max(1, maximumRunMillis / 10)),
                () -> tools.lookupCandidates(List.copyOf(recentIds)));
        if (restored.isEmpty()) {
            state.actions.add("RESTORE_KNOWN_BGG_CANDIDATES_TIMED_OUT");
            return;
        }
        CatalogObservation result = restored.get();
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        state.actions.add(result.succeeded()
                ? "RESTORE_KNOWN_BGG_CANDIDATES"
                : "RESTORE_KNOWN_BGG_CANDIDATES_UNAVAILABLE");
    }

    private List<Map<String, String>> conversationEvidence(ConversationRequest request) {
        Map<String, String> evidence = evidenceReview.preferenceEvidence(request);
        int userIndex = 0;
        List<Map<String, String>> conversation = new ArrayList<>();
        for (DialogueMessage message : request.transcript()) {
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", message.role());
            turn.put("text", message.text());
            if ("user".equals(message.role())) {
                turn.put("evidenceId", "U" + (++userIndex));
            }
            conversation.add(Map.copyOf(turn));
        }
        if (userIndex != evidence.size()) {
            throw new IllegalStateException("recommendation evidence indexing is inconsistent");
        }
        return List.copyOf(conversation);
    }

    private String agentInput(ConversationRequest request, RecommendationAgentState state, String locale) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("locale", locale);
            data.put("currentProfile", evidenceReview.profileForAgent(state.profile));
            if (state.explicitRecommendationCount != null) {
                data.put("explicitRecommendationCount", state.explicitRecommendationCount);
            }
            data.put("contextualAssumptions", state.contextualPreferences.values().stream()
                    .map(value -> Map.of(
                            "field", value.field(),
                            "value", value.value(),
                            "evidenceId", value.evidenceId()))
                    .toList());
            data.put("recentConversation", conversationEvidence(request));
            data.put("focusedBggId", request.focusedBggId());
            data.put("knownGames", request.knownGames().stream()
                    .limit(24)
                    .map(game -> Map.of(
                            "bggId", game.bggId(),
                            "name", game.name(),
                            "originalName", game.originalName()))
                    .toList());
            if (!state.verified.isEmpty()) {
                data.put("restoredRunMemory", runMemory(state));
            }
            data.put("shownBggIds", tail(request.shownBggIds(), 24));
            data.put("excludedBggIds", tail(request.excludedBggIds(), 24));
            data.put("availableCapabilities", Map.of(
                    "semanticPublicDiscovery", tools.webResearchConfigured(),
                    "subjectiveFitResearch", tools.webResearchConfigured()));
            data.put("executionBudget", Map.of(
                    "maximumModelCalls", MAX_MODEL_CALLS,
                    "maximumActionCalls", MAX_ACTION_CALLS));
            data.put("goal", "Continue the player's current conversation naturally. Choose exactly one next action.");
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private static String systemPromptV2() {
        return """
                You are RulePilot, a warm and capable board-game conversation partner. Read the complete recent conversation, continue corrections and references in context, answer in the player's locale and requested level of detail, and call exactly one supplied action. Never expose reasoning, schemas, tool names, or validation internals. Retrieval actions continue this run; reply, ask, compare, no-match, and recommend actions finish it. Do the useful work now instead of promising it for later.

                Ask one easy, high-information question only when a genuinely missing player choice would materially change the candidates. Briefly explain why the choice matters and offer two or three direct options when useful. Empty profile fields do not block an actionable request. Store only explicit numeric/type constraints or a complete-group count supported by the cited user turn; result count is not player count, qualitative taste is not a numeric filter, and later corrections replace earlier values.

                Use the model to understand ordinary language, metaphors, aliases, people, awards, publishers, and other relationships. Resolve a player-authored game title as a title; use public discovery for an external relationship or changing fact, then verify every discovered game through BGG before recommending it. A TARGET_GAME resolution publishes the verified target card in that same action, so include its short natural message and do not plan a second response turn. Browse the filtered catalog for hard numeric/type constraints. Avoid repeated reads: discovery and title inspection already return hydrated games, and runMemory is authoritative.

                Recommend only verified, hard-eligible IDs and honor the requested result count when enough exist. For every card, write a natural, specific why and a useful tradeoff; cite one or more same-candidate observation IDs in internalEvidenceIds, but never show those IDs to the player. Explain what is a verified fact and what is your judgment without turning every sentence into a disclaimer. Taxonomy may inform a suggestion but is not proof of a particular table experience. The terminal message should connect the choices to the conversation in useful prose. Preserve supported content, keep local uncertainty local, and finish as soon as the evidence is sufficient.
                """;
    }

    private List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> preferenceEvidenceIds) {
        List<Integer> recommendableIds = recommendableIds(state);
        List<Integer> comparableIds = comparableIds(state);
        List<String> relaxableSubjects = relaxableSubjects(state);
        boolean comparisonNeedsCandidateInspection = state.namedGamePurpose == NamedGamePurpose.COMPARISON_REFERENCE
                && !state.titleInspectionAttempted;
        boolean verifiedTargetCanFinish = state.namedGamePurpose == NamedGamePurpose.TARGET_GAME
                && state.targetGameIds.stream().anyMatch(recommendableIds::contains);
        boolean verifiedSlateAvailable = !recommendableIds.isEmpty();
        boolean clarificationWouldMaskFailure = state.clarificationBlockedByExecutionFailure
                || state.titleInspectionAttempted && state.verified.isEmpty()
                || state.catalogBrowseAttempted && state.verified.isEmpty()
                || state.discoveryAttempted && state.verified.isEmpty();
        return actions.stream()
                .filter(action -> !verifiedTargetCanFinish || RECOMMEND_TOOL.equals(action.name()))
                .filter(action -> !comparisonNeedsCandidateInspection
                        || !REPLY_TOOL.equals(action.name()) && !ASK_TOOL.equals(action.name()))
                .filter(action -> !clarificationWouldMaskFailure || !ASK_TOOL.equals(action.name()))
                .filter(action -> state.webResearchAvailable
                        || !DISCOVER_TOOL.equals(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verified.isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !RECOMMEND_TOOL.equals(action.name()) || !recommendableIds.isEmpty())
                .filter(action -> state.legalIds.stream().anyMatch(id -> !state.verified.containsKey(id))
                        || !LOOKUP_TOOL.equals(action.name()))
                .filter(action -> state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                        || !RESOLVE_TOOL.equals(action.name()))
                .filter(action -> !state.titleInspectionAttempted || !SEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.catalogBrowseAttempted || !BROWSE_TOOL.equals(action.name()))
                .filter(action -> !state.discoveryAttempted || !DISCOVER_TOOL.equals(action.name()))
                .filter(action -> !state.discoveryProducedVerifiedGames || !BROWSE_TOOL.equals(action.name()))
                .filter(action -> !state.discoveryAttempted || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> comparableIds.size() >= 2 || !COMPARE_TOOL.equals(action.name()))
                .filter(action -> !relaxableSubjects.isEmpty() || !NO_MATCH_TOOL.equals(action.name()))
                .filter(action -> !verifiedSlateAvailable
                        || REPLY_TOOL.equals(action.name())
                        || COMPARE_TOOL.equals(action.name())
                        || RECOMMEND_TOOL.equals(action.name())
                        || state.titleInspectionAttempted
                                && (BROWSE_TOOL.equals(action.name()) || DISCOVER_TOOL.equals(action.name())))
                .map(action -> RECOMMEND_TOOL.equals(action.name())
                        ? recommendationAction(
                                recommendationMinimumCount(state, recommendableIds),
                                recommendationMaximumCount(state, recommendableIds),
                                recommendableIds,
                                recommendableNarrativeEvidenceIds(state, recommendableIds),
                                preferenceEvidenceIds,
                                availabilityShortfall(state, recommendableIds))
                        : COMPARE_TOOL.equals(action.name())
                                ? comparisonAction(comparableIds)
                        : NO_MATCH_TOOL.equals(action.name())
                                ? noMatchAction(relaxableSubjects)
                        : !recommendableIds.isEmpty() && REPLY_TOOL.equals(action.name())
                                ? slateReplyAction()
                        : action)
                .map(action -> preferencesCapturedThisRun(state)
                        ? withoutPreferenceUpdates(action)
                        : action)
                .toList();
    }

    private boolean preferencesCapturedThisRun(RecommendationAgentState state) {
        return state.actions.stream().anyMatch(action -> Set.of(
                        "UPDATE_PREFERENCES",
                        "RECORD_CONTEXTUAL_PREFERENCE",
                        "IGNORED_REDUNDANT_PREFERENCE_UPDATE")
                .contains(action));
    }

    private ToolSpec withoutPreferenceUpdates(ToolSpec action) {
        try {
            JsonNode schema = json.readTree(action.inputSchema());
            if (schema.path("properties") instanceof ObjectNode properties) {
                properties.remove("preferenceUpdates");
            }
            return new ToolSpec(action.name(), action.description(), json.writeValueAsString(schema));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation action schema could not be narrowed", exception);
        }
    }

    int maximumRecommendationResults() {
        return Math.min(MAX_VERIFIED_GAMES, properties.modelCandidateLimit());
    }

    private List<Integer> comparableIds(RecommendationAgentState state) {
        return state.comparisonSubjectIds.stream()
                .filter(state.verified::containsKey)
                .limit(5)
                .toList();
    }

    private List<String> recommendableNarrativeEvidenceIds(
            RecommendationAgentState state,
            List<Integer> recommendableIds) {
        return recommendableIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .flatMap(game -> selector.observations(game).stream())
                .map(CandidateObservation::id)
                .distinct()
                .limit(40)
                .toList();
    }

    private int recommendationMinimumCount(
            RecommendationAgentState state,
            List<Integer> recommendableIds) {
        if (state.explicitRecommendationCount == null) return 1;
        return Math.min(state.explicitRecommendationCount, recommendableIds.size());
    }

    private int recommendationMaximumCount(
            RecommendationAgentState state,
            List<Integer> recommendableIds) {
        if (state.explicitRecommendationCount != null) {
            return recommendationMinimumCount(state, recommendableIds);
        }
        return Math.min(state.maximumRecommendationResults, recommendableIds.size());
    }

    private AvailabilityShortfall availabilityShortfall(
            RecommendationAgentState state,
            List<Integer> recommendableIds) {
        Integer requested = state.explicitRecommendationCount;
        if (requested == null || recommendableIds.size() >= requested) return null;
        return new AvailabilityShortfall(
                requested,
                recommendableIds.size(),
                shortfallRelaxableSubjects(state.profile));
    }

    List<String> shortfallRelaxableSubjects(RecommendationProfile profile) {
        List<String> subjects = new ArrayList<>();
        if (profile.durationMinutes() != null) subjects.add("durationMinutes");
        if (profile.complexity() != null) subjects.add("complexity");
        return List.copyOf(subjects);
    }

    List<String> relaxableSubjects(RecommendationAgentState state) {
        if (state.verified.isEmpty() || !recommendableIds(state).isEmpty()) return List.of();
        LinkedHashSet<String> actionable = new LinkedHashSet<>();
        for (Game game : state.verified.values()) {
            List<CandidateClaim> hardClaims = selector.fitClaims(game, state.profile, false).stream()
                    .filter(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                    .filter(claim -> claim.strength() == ConstraintRange.Strength.HARD)
                    .toList();
            hardClaims.stream()
                    .filter(claim -> claim.relation() != CandidateClaim.Relation.SATISFIED)
                    .filter(claim -> hardClaims.stream()
                            .filter(other -> !other.subject().equals(claim.subject()))
                            .allMatch(other -> other.relation() == CandidateClaim.Relation.SATISFIED))
                    .map(CandidateClaim::subject)
                    .forEach(actionable::add);
        }
        return actionable.stream().limit(3).toList();
    }

    List<Integer> recommendableIds(RecommendationAgentState state) {
        return state.verified.values().stream()
                .filter(game -> !state.excludedIds.contains(game.ranking().bggId()))
                .filter(game -> !state.previouslyShownIds.contains(game.ranking().bggId())
                        || state.targetGameIds.contains(game.ranking().bggId()))
                .filter(game -> !state.comparisonReferenceIds.contains(game.ranking().bggId()))
                .filter(game -> selector.eligible(game, state.profile))
                .map(game -> game.ranking().bggId())
                .toList();
    }

    private static List<ToolSpec> actions(int maximumResultCount, List<String> preferenceEvidenceIds) {
        String preferences = preferenceSchema(preferenceEvidenceIds);
        return List.of(
                new ToolSpec(
                        REPLY_TOOL,
                        "Terminal natural reply only when the current goal is already answered without new cards. Never confirm or promise recommendation work for later; continue retrieval in this run instead. New candidates require cards.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"message\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200},\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"message\"]}"),
                new ToolSpec(
                        ASK_TOOL,
                        "Ask one high-information question only when a missing player-owned choice materially changes candidates. Preserve already-stated hard numeric constraints in preferenceUpdates; never infer the missing answer. In two short sentences, explain the impact and ask one easy question; add two or three options when useful. Never ask after an action, provider, or retrieval failure.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"Two short locale-matched sentences: impact, then one easy question. End with one question mark.\",\"minLength\":12,\"maxLength\":500},\"options\":{\"type\":\"array\",\"description\":\"Optional two or three verbatim answers.\",\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":60}},\"preferenceUpdates\":"
                                + clarificationPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"question\"]}"),
                new ToolSpec(
                        RESOLVE_TOOL,
                        "Resolve one intact player-authored board-game title and declare its role. Never translate, trim, or guess it; people, awards, publishers, lists, and relationship phrases need public discovery. TARGET_GAME must include a short natural message and finishes immediately when identity verification succeeds.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160},\"purpose\":{\"type\":\"string\",\"enum\":[\"TARGET_GAME\",\"COMPARISON_REFERENCE\",\"DISCUSSION_SUBJECT\",\"IDENTITY_ONLY\"]},\"message\":{\"type\":\"string\",\"description\":\"Required for TARGET_GAME only: one short locale-matched sentence grounded only in the player choosing this exact title; successful verification publishes the selectable card in this same action.\",\"minLength\":1,\"maxLength\":1200},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"title\",\"purpose\"]}"),
                new ToolSpec(
                        SEARCH_TOOL,
                        "Inspect one to eight generated original/English candidate titles. Never include a player-named title. Results are already BGG-verified.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"titles\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":120}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"titles\"]}"),
                new ToolSpec(
                        BROWSE_TOOL,
                        "Browse broad BGG catalog candidates using optional ranking domains and the persisted hard profile. Do not use as semantic similarity search.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"preferenceUpdates\":"
                                + preferences
                                + "}}"),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Search public sources once for an external relationship, changing fact, or after title inspection yields no useful slate. Results are resolved through BGG.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":3,\"maxLength\":300},\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"query\"]}"),
                new ToolSpec(
                        LOOKUP_TOOL,
                        "Load BGG facts only for observed conversation-context IDs that do not yet have verified details.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"integer\",\"minimum\":1}}},\"required\":[\"bggIds\"]}"),
                new ToolSpec(
                        RESEARCH_TOOL,
                        "Research an explicit, separate current-reception or player-reported-experience question for one to five verified games. Do not use for ordinary recommendation fit or after public discovery.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"question\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":300}},\"required\":[\"bggIds\",\"question\"]}"),
                comparisonAction(List.of()),
                noMatchAction(List.of()),
                recommendationAction(1, maximumResultCount, List.of(), List.of(), preferenceEvidenceIds, null));
    }

    private static ToolSpec comparisonAction(List<Integer> comparableIds) {
        String idConstraint = comparableIds.isEmpty()
                ? "\"minimum\":1"
                : "\"enum\":" + comparableIds;
        return new ToolSpec(
                COMPARE_TOOL,
                "Compare two to five verified conversation candidates on one to three observed axes. Write one useful natural answer, including a choice when the player asked for one. Never use this to replace candidates.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"message\":{\"type\":\"string\",\"description\":\"A natural comparison grounded in runMemory. Separate observed facts from judgment without boilerplate and answer the player's actual decision.\",\"minLength\":1,\"maxLength\":1200},\"candidateBggIds\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"integer\","
                        + idConstraint
                        + "}},\"subjects\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(COMPARISON_SUBJECTS.stream().sorted().toList())
                        + "}}},\"required\":[\"message\",\"candidateBggIds\",\"subjects\"]}");
    }

    private static ToolSpec noMatchAction(List<String> relaxableSubjects) {
        String subjectConstraint = relaxableSubjects.isEmpty()
                ? "\"minLength\":1,\"maxLength\":40"
                : "\"enum\":" + jsonArray(relaxableSubjects);
        return new ToolSpec(
                NO_MATCH_TOOL,
                "Finish with zero cards and one application-validated hard-filter relaxation. Never relax it without player confirmation.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"relaxSubject\":{\"type\":\"string\","
                        + subjectConstraint
                        + "}},\"required\":[\"relaxSubject\"]}");
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static ToolSpec recommendationAction(
            int minimumResultCount,
            int maximumResultCount,
            List<Integer> recommendableIds,
            List<String> narrativeEvidenceIds,
            List<String> preferenceEvidenceIds,
            AvailabilityShortfall shortfall) {
        String idConstraint = recommendableIds.isEmpty()
                ? "\"minimum\":1"
                : "\"enum\":" + recommendableIds;
        String narrativeEvidenceConstraint = narrativeEvidenceIds.isEmpty()
                ? "\"minLength\":3,\"maxLength\":80"
                : "\"enum\":" + jsonArray(narrativeEvidenceIds);
        String selectionSchema = "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{"
                + "\"bggId\":{\"type\":\"integer\"," + idConstraint + "},"
                + "\"why\":{\"type\":\"string\",\"description\":\"A natural, candidate-specific reason connected to the player's request. Distinguish observed facts from your judgment without boilerplate. Never include internal evidence IDs.\",\"minLength\":8,\"maxLength\":500},"
                + "\"tradeoff\":{\"type\":\"string\",\"description\":\"One concrete limitation, uncertainty, or choice-relevant tradeoff for this candidate. Never include internal evidence IDs.\",\"minLength\":4,\"maxLength\":320},"
                + "\"internalEvidenceIds\":{\"type\":\"array\",\"description\":\"One to five same-candidate observations that informed why or tradeoff. Machine-only; never copy IDs into player text.\",\"minItems\":1,\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\"," + narrativeEvidenceConstraint + "}}"
                + "},\"required\":[\"bggId\",\"why\",\"tradeoff\",\"internalEvidenceIds\"]}";
        String shortfallProperty = shortfall == null ? "" : ",\"shortfall\":" + shortfallSchema(shortfall);
        String required = shortfall == null
                ? "[\"message\",\"selections\"]"
                : "[\"message\",\"selections\",\"shortfall\"]";
        String availabilityGuidance = shortfall == null
                ? ""
                : " The player requested " + shortfall.requestedCount()
                        + " cards, but exactly " + shortfall.availableCount()
                        + " hard-eligible IDs are available. Return every available ID once, never duplicate or pad. The message must plainly explain this shortfall. Fill shortfall with the exact counts and concrete direct-reply relaxation options only for its allowed subjects; never promise that relaxing one guarantees another result.";
        return new ToolSpec(
                RECOMMEND_TOOL,
                "Return verified IDs with natural candidate-specific reasons and tradeoffs. Use same-candidate observations as internal evidence. When fewer hard-eligible games exist than requested, return every available ID once plus shortfall."
                        + availabilityGuidance,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"message\":{\"type\":\"string\",\"description\":\"Natural player-facing synthesis that explains how the selected games relate to this conversation.\",\"minLength\":1,\"maxLength\":1200},\"referenceBggIds\":{\"type\":\"array\",\"description\":\"Omit unless the player named a comparison game. Never put selected candidates here.\",\"maxItems\":2,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"selections\":{\"type\":\"array\",\"description\":\"Native JSON array of selection objects.\",\"minItems\":"
                        + minimumResultCount
                        + ",\"maxItems\":"
                        + maximumResultCount
                        + ",\"uniqueItems\":true,\"items\":"
                        + selectionSchema
                        + "}"
                        + shortfallProperty
                        + ",\"preferenceUpdates\":"
                        + preferenceSchema(preferenceEvidenceIds)
                        + "},\"required\":"
                        + required
                        + "}");
    }

    private static String shortfallSchema(AvailabilityShortfall shortfall) {
        int minimumOptions = shortfall.relaxableSubjects().isEmpty() ? 0 : 1;
        int maximumOptions = Math.min(2, shortfall.relaxableSubjects().size());
        String subjectConstraint = shortfall.relaxableSubjects().isEmpty()
                ? "[]"
                : jsonArray(shortfall.relaxableSubjects());
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"requestedCount\":{\"type\":\"integer\",\"enum\":[" + shortfall.requestedCount() + "]},"
                + "\"availableCount\":{\"type\":\"integer\",\"enum\":[" + shortfall.availableCount() + "]},"
                + "\"relaxationOptions\":{\"type\":\"array\",\"minItems\":" + minimumOptions
                + ",\"maxItems\":" + maximumOptions
                + ",\"uniqueItems\":true,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"subject\":{\"type\":\"string\",\"enum\":" + subjectConstraint + "},"
                + "\"reply\":{\"type\":\"string\",\"description\":\"One short, concrete first-person reply the player can select to relax only this bound. Do not claim it guarantees another match.\",\"minLength\":4,\"maxLength\":120}},"
                + "\"required\":[\"subject\",\"reply\"]}}},"
                + "\"required\":[\"requestedCount\",\"availableCount\",\"relaxationOptions\"]}";
    }

    private record AvailabilityShortfall(
            int requestedCount,
            int availableCount,
            List<String> relaxableSubjects) {}

    private static ToolSpec slateReplyAction() {
        return new ToolSpec(
                REPLY_TOOL,
                "Terminal reply only when this turn does not request new candidates and its goal is already answered. Never narrate unfinished work. Do not mention retrieved leads.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"message\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200},"
                        + "\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}}},"
                        + "\"required\":[\"message\"]}");
    }

    private static String preferenceSchema(List<String> preferenceEvidenceIds) {
        String evidenceEnum = evidenceEnum(preferenceEvidenceIds);
        return "{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"field\":{\"type\":\"string\",\"enum\":[\"players\",\"playerCount\",\"durationMinutes\",\"complexity\",\"type\",\"interaction\"]},"
                + "\"value\":{\"description\":\"players=N is an exact current group; playerCount is only an explicit range or endpoint. Duration ceiling N is {minimum:null,maximum:N}. Other numeric fields use ranges; null clears a limit. Result count and qualitative taste are not profile values.\",\"anyOf\":[{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"minimum\":{\"type\":[\"number\",\"null\"]},\"maximum\":{\"type\":[\"number\",\"null\"]}},\"required\":[\"minimum\",\"maximum\"]},{\"type\":\"null\"},{\"type\":\"string\",\"enum\":[\"ALL\",\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\",\"ANY\",\"COMPETITIVE\",\"COOPERATIVE\",\"TEAM\"]}]},"
                + "\"evidence\":{\"type\":\"string\",\"description\":\"Use the exact evidenceId from the latest user message. A later explicit correction replaces currentProfile; never cite superseded evidence.\",\"enum\":"
                + evidenceEnum
                + "},\"evidenceClassification\":{\"type\":\"string\",\"description\":\"One atomic classification. DIRECT means the cited user turn explicitly states this exact constraint. CONTEXTUAL_COMPLETE_GROUP is only a reversible exact player count inferred from a fully described whole group; never use a count that describes only a subgroup.\",\"enum\":[\"DIRECT\",\"CONTEXTUAL_COMPLETE_GROUP\"]}},"
                + "\"required\":[\"field\",\"value\",\"evidence\",\"evidenceClassification\"]}}";
    }

    private static String clarificationPreferenceSchema(List<String> preferenceEvidenceIds) {
        return "{\"type\":\"array\",\"description\":\"Already-stated direct numeric constraints only; omit the missing answer.\",\"minItems\":1,\"maxItems\":4,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"field\":{\"type\":\"string\",\"enum\":[\"players\",\"playerCount\",\"durationMinutes\",\"complexity\"]},"
                + "\"value\":{\"anyOf\":[{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"minimum\":{\"type\":[\"number\",\"null\"]},\"maximum\":{\"type\":[\"number\",\"null\"]}},\"required\":[\"minimum\",\"maximum\"]}]},"
                + "\"evidence\":{\"type\":\"string\",\"enum\":"
                + evidenceEnum(preferenceEvidenceIds)
                + "}},\"required\":[\"field\",\"value\",\"evidence\"]}}";
    }

    private static String evidenceEnum(List<String> preferenceEvidenceIds) {
        List<String> allowedEvidenceIds = preferenceEvidenceIds.isEmpty()
                ? List.of("NO_USER_EVIDENCE")
                : preferenceEvidenceIds;
        return allowedEvidenceIds.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private Map<String, Object> runMemory(RecommendationAgentState state) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("profile", evidenceReview.profileForAgent(state.profile));
        putIfNotEmpty(memory, "contextualAssumptions", state.contextualPreferences.values().stream()
                .map(value -> Map.of(
                        "field", value.field(),
                        "value", value.value(),
                        "evidenceId", value.evidenceId()))
                .toList());
        putIfNotEmpty(memory, "candidateLeads", state.candidateNames.entrySet().stream()
                .filter(entry -> !state.verified.containsKey(entry.getKey()))
                .limit(MAX_OBSERVED_CANDIDATES)
                .map(entry -> Map.of("bggId", entry.getKey(), "name", entry.getValue()))
                .toList());
        putIfNotEmpty(memory, "otherObservedBggIds", state.legalIds.stream()
                .filter(id -> !state.candidateNames.containsKey(id) && !state.verified.containsKey(id))
                .limit(MAX_OBSERVED_CANDIDATES)
                .toList());
        memory.put("verifiedGames", state.verified.values().stream()
                .map(actionExecutor::gameObservation)
                .toList());
        memory.put("recommendableBggIds", recommendableIds(state));
        putIfNotEmpty(memory, "previouslyShownBggIds", state.previouslyShownIds.stream().toList());
        putIfNotEmpty(memory, "targetGameBggIds", state.targetGameIds.stream().toList());
        putIfNotEmpty(memory, "comparisonReferenceBggIds", state.comparisonReferenceIds.stream().toList());
        memory.put("referenceResolutionAttempts", state.referenceResolutionAttempts);
        if (state.namedGamePurpose != null) memory.put("namedGamePurpose", state.namedGamePurpose.name());
        putIfNotEmpty(memory, "researchEvidence", state.research.games().stream()
                .limit(MAX_VERIFIED_GAMES)
                .map(game -> Map.of(
                        "bggId", game.bggId(),
                        "observations", game.observations().stream()
                                .limit(2)
                                .map(item -> Map.of(
                                        "text", bounded(item.text(), 240),
                                        "sourceIndexes", item.sourceIndexes().stream().limit(3).toList()))
                                .toList()))
                .toList());
        putIfNotEmpty(memory, "researchSources", actionExecutor.sourceObservations(state.research.sources()));
        memory.put("actionsTaken", state.actions.stream()
                .skip(Math.max(0, state.actions.size() - 12L))
                .toList());
        if (!state.webResearchAvailable && !state.webResearchFailureCode.isBlank()) {
            memory.put("webResearchFailureCode", state.webResearchFailureCode);
        }
        return memory;
    }

    private void putIfNotEmpty(Map<String, Object> target, String field, List<?> values) {
        if (!values.isEmpty()) target.put(field, values);
    }

    private Map<String, Boolean> availableCapabilities(RecommendationAgentState state) {
        return Map.of(
                "semanticPublicDiscovery",
                        state.webResearchAvailable && !state.discoveryAttempted,
                "subjectiveFitResearch", state.webResearchAvailable && !state.discoveryAttempted);
    }

    String observation(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation could not be serialized", exception);
        }
    }

    private String budgetedObservation(String observation, RecommendationAgentState state) {
        try {
            JsonNode parsed = json.readTree(observation);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("recommendation observation must be a JSON object");
            }
            object.put("remainingModelCalls", Math.max(0, MAX_MODEL_CALLS - state.modelCalls));
            object.put("remainingActionCalls", Math.max(0, MAX_ACTION_CALLS - state.actionCalls));
            object.set("availableCapabilities", json.valueToTree(availableCapabilities(state)));
            object.set("runMemory", json.valueToTree(runMemory(state)));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation budget could not be serialized", exception);
        }
    }

    String error(String code, String guidance) {
        return observation(Map.of("status", "ERROR", "code", code, "guidance", guidance));
    }

    ConversationRequest validate(ConversationRequest input) {
        if (input == null) throw new IllegalArgumentException("recommendation conversation request is required");
        String message = RecommendationConversationText.currentTurn(input.message());
        List<Integer> excluded = positiveIds(input.excludedBggIds(), 60, "excludedBggIds");
        Integer focused = input.focusedBggId();
        if (focused != null && focused <= 0) throw new IllegalArgumentException("focusedBggId must be positive");
        List<KnownGame> knownGames = input.knownGames() == null
                ? List.of()
                : input.knownGames().stream()
                        .map(this::validatedKnownGame)
                        .collect(java.util.stream.Collectors.toMap(
                                KnownGame::bggId,
                                java.util.function.Function.identity(),
                                (left, right) -> left,
                                LinkedHashMap::new))
                        .values()
                        .stream()
                        .toList();
        if (knownGames.size() > 60) throw new IllegalArgumentException("knownGames must contain at most sixty games");
        List<Integer> shown = positiveIds(input.shownBggIds(), 60, "shownBggIds");
        List<DialogueMessage> transcript = input.transcript() == null
                ? new ArrayList<>()
                : input.transcript().stream()
                        .map(this::validatedMessage)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!message.isBlank()
                && (transcript.isEmpty()
                        || !"user".equals(transcript.getLast().role())
                        || !message.equals(transcript.getLast().text()))) {
            transcript.add(new DialogueMessage("user", message));
        }
        if (transcript.size() > 12) {
            transcript = new ArrayList<>(transcript.subList(transcript.size() - 12, transcript.size()));
        }
        return new ConversationRequest(
                input.profile() == null ? RecommendationProfile.empty() : validatedProfile(input.profile()),
                message,
                excluded,
                List.copyOf(transcript),
                focused,
                knownGames,
                shown);
    }

    private RecommendationProfile validatedProfile(RecommendationProfile profile) {
        validateIntegerRange(profile.playerCount(), 1, 20, "profile player count");
        validateIntegerRange(profile.durationMinutes(), 5, 1_440, "profile duration");
        validateDecimalRange(profile.complexity(), BigDecimal.ZERO, new BigDecimal("5"), "profile weight");
        return new RecommendationProfile(
                profile.playerCount(),
                profile.durationMinutes(),
                profile.complexity(),
                profile.type() == null ? BggGameType.ALL : profile.type(),
                profile.interaction() == null ? InteractionPreference.ANY : profile.interaction());
    }

    private <T extends Comparable<? super T>> void validateRangeMetadata(ConstraintRange<T> range, String label) {
        if (range == null) return;
        if (range.confirmedTurn() > 10_000) throw new IllegalArgumentException(label + " turn is invalid");
    }

    private void validateIntegerRange(ConstraintRange<Integer> range, int minimum, int maximum, String label) {
        validateRangeMetadata(range, label);
        if (range == null) return;
        if (range.minimum() != null && (range.minimum() < minimum || range.minimum() > maximum)
                || range.maximum() != null && (range.maximum() < minimum || range.maximum() > maximum)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private void validateDecimalRange(
            ConstraintRange<BigDecimal> range,
            BigDecimal minimum,
            BigDecimal maximum,
            String label) {
        validateRangeMetadata(range, label);
        if (range == null) return;
        if (range.minimum() != null
                        && (range.minimum().compareTo(minimum) < 0 || range.minimum().compareTo(maximum) > 0)
                || range.maximum() != null
                        && (range.maximum().compareTo(minimum) < 0 || range.maximum().compareTo(maximum) > 0)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private KnownGame validatedKnownGame(KnownGame game) {
        if (game == null || game.bggId() <= 0) throw new IllegalArgumentException("known game id is invalid");
        String name = normalized(game.name(), 160, true);
        String originalName = normalized(game.originalName(), 160, true);
        if (name.isBlank() && originalName.isBlank()) throw new IllegalArgumentException("known game name is required");
        return new KnownGame(game.bggId(), name, originalName);
    }

    private DialogueMessage validatedMessage(DialogueMessage message) {
        if (message == null || !("user".equals(message.role()) || "assistant".equals(message.role()))) {
            throw new IllegalArgumentException("recommendation transcript role is invalid");
        }
        String text = "user".equals(message.role())
                ? RecommendationConversationText.playerTranscriptTurn(message.text())
                : RecommendationConversationText.assistantTranscriptTurn(message.text());
        return new DialogueMessage(message.role(), text);
    }

    private List<Integer> positiveIds(List<Integer> values, int maximum, String label) {
        List<Integer> result = values == null
                ? List.of()
                : values.stream().filter(Objects::nonNull).distinct().toList();
        if (result.size() > maximum || result.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException(label + " must contain at most " + maximum + " positive ids");
        }
        return result;
    }

    private String normalized(String value, int maximum, boolean allowBlank) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if ((!allowBlank && checked.isBlank()) || checked.length() > maximum) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return checked;
    }

    private List<Integer> tail(List<Integer> values, int maximum) {
        return values.stream().skip(Math.max(0, values.size() - (long) maximum)).toList();
    }

    String bounded(String value, int maximum) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return checked.length() <= maximum ? checked : checked.substring(0, maximum);
    }

    List<String> bounded(List<String> values, int maximumItems, int maximumCharacters) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> bounded(value, maximumCharacters))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(maximumItems)
                .toList();
    }

    <T> T withinDeadline(RecommendationAgentState state, Supplier<T> operation) {
        long remainingMillis = maximumRunMillis - state.elapsedMs();
        if (remainingMillis <= 0) throw new RunDeadlineExceeded();
        Future<T> pending = boundedCalls.submit(operation::get);
        try {
            return pending.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new RunDeadlineExceeded();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunDeadlineExceeded();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("bounded recommendation operation failed", cause);
        }
    }

    private <T> Optional<T> withinSoftDeadline(
            RecommendationAgentState state, long maximumOperationMillis, Supplier<T> operation) {
        long remainingMillis = maximumRunMillis - state.elapsedMs();
        if (remainingMillis <= 0) throw new RunDeadlineExceeded();
        long waitMillis = Math.min(remainingMillis, maximumOperationMillis);
        Future<T> pending = boundedCalls.submit(operation::get);
        try {
            return Optional.ofNullable(pending.get(waitMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            pending.cancel(true);
            if (state.elapsedMs() >= maximumRunMillis) throw new RunDeadlineExceeded();
            return Optional.empty();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunDeadlineExceeded();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("soft-bounded recommendation operation failed", cause);
        }
    }

    private void emitProgress(Consumer<ProgressUpdate> listener, ProgressStage stage, long startedAt) {
        if (listener == null) return;
        try {
            listener.accept(new ProgressUpdate(stage, (System.nanoTime() - startedAt) / 1_000_000));
        } catch (RuntimeException exception) {
            LOGGER.debug("Recommendation progress listener stopped accepting updates");
        }
    }

    boolean chinese(String locale) {
        return "zh-CN".equals(locale);
    }

    private boolean simplifiedChineseLocale(String locale) {
        String value = locale == null ? "" : locale.strip().toLowerCase(Locale.ROOT);
        return value.equals("zh") || value.equals("zh-cn") || value.equals("zh-hans");
    }

    static final class RunDeadlineExceeded extends RuntimeException {}
}
