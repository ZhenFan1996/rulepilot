package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
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
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.NaturalReply;
import com.rulepilot.recommendation.BoardGameRecommendationModel.NaturalReplyRequest;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressAction;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the bounded observe-decide-act loop, budgets, and truthful degradation. */
final class RecommendationReActLoop {

    static final int MAX_MODEL_CALLS = 6;
    private static final int MAX_DECISION_MODEL_CALLS = MAX_MODEL_CALLS - 1;
    private static final int MAX_ACTION_CALLS = 6;
    private static final int ACTION_SELECTION_OUTPUT_TOKENS = 384;
    private static final int EVIDENCE_RESPONSE_OUTPUT_TOKENS = 384;
    private static final String FINAL_RESPONSE_STOP = "⟦END⟧";
    private static final Pattern GREETING = Pattern.compile("^(?:你好|您好|嗨|哈[喽啰]|hello|hi|hey)$");
    private static final Pattern THANKS = Pattern.compile("^(?:谢谢|感谢|多谢|谢啦|thanks|thank you)$");
    private static final Pattern PAUSE = Pattern.compile(
            "^(?:(?:谢谢|感谢|thanks|thank you)[,， ]*)?(?:先)?(?:不用|不要|别)(?:再)?(?:继续)?推荐(?:了)?$"
                    + "|^(?:先)?(?:等一下|等等|暂停|停一下|hold on|pause|stop)$");
    private static final Pattern CAPABILITY = Pattern.compile(
            "^(?:你能做什么|你会做什么|你可以做什么|怎么用|如何使用|help|what can you do|what do you do)$");
    static final int MAX_REFERENCE_RESOLUTION_ATTEMPTS = 2;
    private static final Set<String> READ_ACTIONS = Set.of(
            RESOLVE_TOOL,
            SEARCH_TOOL,
            BROWSE_TOOL,
            DISCOVER_TOOL,
            LOOKUP_TOOL,
            RESEARCH_TOOL);

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
        return converse(
                input,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                ignored -> {});
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener) {
        return converseValidated(
                validate(input),
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener);
    }

    ConversationResponse converseValidated(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        return converseValidated(
                request,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                ignored -> {});
    }

    ConversationResponse converseValidated(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener) {
        long startedAt = System.nanoTime();
        String locale = simplifiedChineseLocale(requestedLocale) ? "zh-CN" : "en";
        RecommendationAgentState state = new RecommendationAgentState(
                request,
                startedAt,
                modelConfigurationOwner,
                tools.webResearchConfigured(),
                maximumRecommendationResults());
        ProgressTracker progress = new ProgressTracker(progressListener, state, startedAt);
        progress.start(ProgressStage.UNDERSTANDING_REQUEST, ProgressAction.UNDERSTAND_REQUEST);
        Optional<DirectConversationKind> directConversation = directConversationTurn(request);
        progress.complete();
        if (directConversation.isPresent()) {
            if (!model.configured(state.modelConfigurationOwner)) {
                progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
                progress.fail();
                return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
            }
            progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
            state.modelCalls++;
            try {
                NaturalReply reply = withinDeadline(
                        state,
                        () -> model.streamNaturalReply(
                                new NaturalReplyRequest(
                                        naturalReplyMessages(request, locale, directConversation.orElseThrow()),
                                        512,
                                        List.of(FINAL_RESPONSE_STOP)),
                                state.modelConfigurationOwner,
                                answerPartListener));
                if (reply.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT
                        || reply.text().isBlank()) {
                    progress.fail();
                    state.actions.add(reply.text().isBlank()
                            ? "NATURAL_REPLY_EMPTY"
                            : "NATURAL_REPLY_OUTPUT_TRUNCATED");
                    return unavailable(state, locale, "NATURAL_REPLY_INVALID");
                }
                progress.complete();
                return naturalReplyResponse(state, locale, directConversation.orElseThrow(), reply.text());
            } catch (RunDeadlineExceeded exception) {
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
                progress.fail();
                LOGGER.warn("Recommendation natural reply stream failed ({})", exception.getClass().getSimpleName());
                state.actions.add("NATURAL_REPLY_FAILED");
                return unavailable(state, locale, "NATURAL_REPLY_FAILED");
            }
        }
        Optional<String> explicitTargetTitle = BoardGameTitleGrounding.explicitTargetTitle(request.message());
        if (explicitTargetTitle.isPresent()) {
            state.actionCalls++;
            progress.start(ProgressStage.READING_GAME_DETAILS, ProgressAction.RESOLVE_BGG_GAME);
            boolean exactTarget = actionExecutor.resolveExplicitTarget(
                    explicitTargetTitle.orElseThrow(),
                    state,
                    request,
                    locale,
                    progress);
            progress.complete();
            if (exactTarget) return streamExplicitTargetReply(
                    request,
                    state,
                    locale,
                    progress,
                    answerPartListener);
        }
        if (!model.configured(state.modelConfigurationOwner)) {
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            progress.fail();
            return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
        }
        List<String> preferenceEvidenceIds = evidenceReview.preferenceEvidence(request).keySet().stream().toList();
        List<ToolSpec> actions = actions(state.maximumRecommendationResults, preferenceEvidenceIds);

        List<Message> foundation = List.of(
                Message.system(systemPromptV2()),
                Message.user(agentInput(request, state, locale)));
        List<Message> messages = new ArrayList<>(foundation);
        Set<String> executed = new LinkedHashSet<>();
        boolean unstructuredReplyRetried = false;

        // A successful structural decision is not player-visible until the model has streamed the final voice.
        // Keep that final call inside the same hard budget instead of silently allowing a seventh call.
        while (state.modelCalls < MAX_DECISION_MODEL_CALLS && state.actionCalls < MAX_ACTION_CALLS) {
            state.modelCalls++;
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = availableActions(state, actions, preferenceEvidenceIds);
            try {
                List<Message> turnMessages = messages;
                turn = withinDeadline(
                        state,
                        () -> model.next(
                                new Request(
                                        turnMessages,
                                        currentActions,
                                        outputTokenBudget(state),
                                        ToolChoice.REQUIRED),
                                state.modelConfigurationOwner));
            } catch (RunDeadlineExceeded exception) {
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
                progress.fail();
                LOGGER.warn("Recommendation ReAct turn failed ({})", exception.getClass().getSimpleName());
                state.actions.add("MODEL_CALL_FAILED");
                return unavailable(state, locale, "MODEL_CALL_FAILED");
            }
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                progress.fail();
                state.actions.add("MODEL_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "MODEL_OUTPUT_TRUNCATED");
            }
            if (turn.toolCalls().isEmpty()) {
                if (!turn.text().isBlank()) {
                    if (!unstructuredReplyRetried) {
                        progress.retry();
                        unstructuredReplyRetried = true;
                        boolean externalEvidenceRead = state.catalogCalls > 0 || state.webResearchCalls > 0;
                        state.actions.add(externalEvidenceRead
                                ? "REJECTED_UNSTRUCTURED_EVIDENCE_REPLY"
                                : "REJECTED_UNSTRUCTURED_REPLY");
                        messages = new ArrayList<>(messages);
                        messages.add(new Message(
                                BoardGameRecommendationModel.Role.ASSISTANT,
                                turn.text(),
                                List.of(),
                                null,
                                null));
                        messages.add(Message.system(externalEvidenceRead
                                ? "The preceding prose cannot be published after external evidence was read because "
                                        + "it bypasses the candidate-scoped evidence boundary. Keep any useful natural "
                                        + "wording, but now call exactly one supplied terminal action: reply_to_user for "
                                        + "a sourced prose answer, compare_candidates for a comparison, or recommend_games for "
                                        + "selectable cards. Do not perform another read."
                                : "The preceding unstructured prose cannot be published because every turn must finish "
                                        + "through one supplied action. If the current request is ordinary conversation, "
                                        + "preserve the useful wording in reply_to_user. If it asks to find a named game, "
                                        + "call resolve_bgg_game; if it asks for selectable candidates, call the appropriate "
                                        + "retrieval or card action now. Do not claim the requested external work is complete in prose."));
                        continue;
                    }
                    progress.fail();
                    state.actions.add("REPEATED_UNSTRUCTURED_REPLY");
                    return unavailable(state, locale, "UNSTRUCTURED_REPLY");
                }
                progress.fail();
                LOGGER.warn("Recommendation ReAct turn returned neither a direct reply nor an action");
                state.actions.add("EMPTY_MODEL_TURN");
                return unavailable(state, locale, "EMPTY_MODEL_TURN");
            }
            ToolCall call;
            if (turn.toolCalls().size() == 1) {
                call = turn.toolCalls().getFirst();
            } else {
                String actionName = turn.toolCalls().getFirst().name();
                boolean sameReadAction = READ_ACTIONS.contains(actionName)
                        && turn.toolCalls().stream().allMatch(candidate -> actionName.equals(candidate.name()));
                if (!sameReadAction) {
                    progress.fail();
                    LOGGER.warn(
                            "Recommendation ReAct turn returned {} incompatible actions (textCharacters={})",
                            turn.toolCalls().size(),
                            turn.text().length());
                    state.actions.add("INVALID_ACTION_COUNT");
                    return unavailable(state, locale, "INVALID_ACTION_COUNT");
                }
                // Some compatible providers emit parallel alternatives even when parallel calls are
                // disabled. These capabilities are side-effect-free reads, so one bounded read is enough;
                // executing every variant would add cost without giving the model an observation between them.
                call = turn.toolCalls().getFirst();
                state.actions.add("COALESCED_PARALLEL_READ_ACTIONS:" + turn.toolCalls().size());
            }
            progress.complete();
            state.actionCalls++;
            String fingerprint = call.name() + "\n" + call.argumentsJson();
            RecommendationActions.ActionOutcome outcome;
            progress.start(progressStage(call.name()), progressAction(call.name()));
            if (currentActions.stream().noneMatch(action -> action.name().equals(call.name()))) {
                state.actions.add("REJECTED_UNAVAILABLE_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.observation(error(
                        "ACTION_NOT_AVAILABLE",
                        "That capability is not available in this turn. Choose one action from the supplied list."));
            } else if (executed.contains(fingerprint)) {
                state.actions.add("REJECTED_REPEATED_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.observation(error(
                        "REPEATED_ACTION",
                        "This exact action already ran. Use its observation and choose a materially different next action."));
            } else {
                outcome = actionExecutor.execute(call, state, request, locale, progress);
                if (state.actions.isEmpty()
                        || !state.actions.getLast().startsWith("REJECTED_ACTION:")) {
                    executed.add(fingerprint);
                }
            }
            if (outcome.response() != null) {
                progress.complete();
                return streamValidatedFinalResponse(
                        request,
                        state,
                        locale,
                        progress,
                        outcome.response(),
                        answerPartListener);
            }
            if (state.actions.getLast().startsWith("REJECTED_ACTION:")
                    || state.actions.getLast().equals("REJECTED_UNAVAILABLE_ACTION")
                    || state.actions.getLast().equals("REJECTED_REPEATED_ACTION")) {
                progress.retry();
            } else {
                progress.complete();
            }
            String observation = budgetedObservation(outcome.observation(), state);
            messages = new ArrayList<>(foundation);
            messages.add(Message.assistant(turn.text(), call));
            messages.add(Message.tool(call, observation));
        }
        progress.fail();
        state.actions.add("REACT_BUDGET_EXHAUSTED");
        return unavailable(state, locale, "BUDGET_EXHAUSTED");
    }

    private ConversationResponse streamValidatedFinalResponse(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            ProgressTracker progress,
            ConversationResponse decision,
            Consumer<String> answerPartListener) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
        state.modelCalls++;
        try {
            NaturalReply reply = withinDeadline(
                    state,
                    () -> model.streamNaturalReply(
                            new NaturalReplyRequest(
                                    finalResponseMessages(request, state, decision, locale),
                                    1_024,
                                    List.of(FINAL_RESPONSE_STOP)),
                            state.modelConfigurationOwner,
                            answerPartListener));
            if (reply.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT
                    || reply.text().isBlank()) {
                progress.fail();
                state.actions.add(reply.text().isBlank()
                        ? "FINAL_RESPONSE_EMPTY"
                        : "FINAL_RESPONSE_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "FINAL_RESPONSE_INVALID");
            }
            progress.complete();
            var clarification = decision.clarification() == null
                    ? null
                    : new BoardGameRecommendationAgent.Clarification(
                            decision.clarification().field(),
                            reply.text(),
                            decision.clarification().options());
            ConversationResponse response = new ConversationResponse(
                    decision.outcome(),
                    decision.mode(),
                    reply.text(),
                    decision.profile(),
                    clarification,
                    decision.sourceCount(),
                    decision.candidatesEvaluated(),
                    decision.userModel(),
                    decision.researchSources(),
                    new HarnessTrace(
                            state.modelCalls,
                            state.catalogCalls,
                            state.webResearchCalls,
                            false,
                            state.actions,
                            state.elapsedMs()),
                    decision.games(),
                    decision.comparison(),
                    decision.shortfall());
            logRun(response);
            return response;
        } catch (RunDeadlineExceeded exception) {
            progress.fail();
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
        } catch (RuntimeException exception) {
            progress.fail();
            LOGGER.warn("Recommendation final response stream failed ({})", exception.getClass().getSimpleName());
            state.actions.add("FINAL_RESPONSE_STREAM_FAILED");
            return unavailable(state, locale, "FINAL_RESPONSE_STREAM_FAILED");
        }
    }

    private List<Message> finalResponseMessages(
            ConversationRequest request,
            RecommendationAgentState state,
            ConversationResponse decision,
            String locale) {
        String system = """
                You are RulePilot's final player-facing voice. The recommendation Agent has already made the decision. Game identities, the player's explicit numeric/type constraints and exclusions, selected observation ownership, result count, and publication permissions have been checked. Write the actual response in the requested locale as natural conversation, not a status report or a template.

                Preserve the selected games, comparison preference, shortfall, or clarification represented by validatedDecision. Do not invent another title, change an explicit constraint, add a relaxation, or claim that work will happen later. Lead with the useful judgment and its decisive reason. Add a concrete contrast only when the supplied observations support both sides; never invent a tradeoff merely to complete a response shape. React to the player's latest wording instead of reciting their profile.

                Every game-specific factual clause must stay within allowedGames.observations. The first item in each observation is its source kind; never print that kind or the observation ID. A publisher description supports only the premise, setting, components, or advertised feature it literally states. A taxonomy label proves only that classification. Neither proves ease, pace, depth, tension, accessibility, interaction quality, consequences, or actual player experience. In particular, call a game cooperative, competitive, team-based, or solo only when an allowed observation literally supplies that classification or description; mechanics such as drafting, collection, trading, or communication do not imply it. An attributed report supports only its literal report. You may make a recommendation judgment, but do not disguise an unobserved experience claim as that judgment. If a requested quality is unsupported and materially affects the choice, say so once in plain language; otherwise omit it instead of adding a generic disclaimer.

                For needs_clarification, ask exactly one easy question aligned with the supplied options. For no_match or a recommendation shortfall, describe the real tradeoff without promising that relaxing one condition guarantees a result. For an exact selected target, acknowledge only the verified identity and point naturally to the card.

                Be concise without sounding clipped: normally write 2–4 complete sentences, at most 220 Chinese characters or 120 English words. Speak to the player; do not say metadata, hard constraint, validation, or factual allowance. No headings, bullets, evidence markers, Markdown table, generic disclaimer, schemas, tools, prompts, model calls, or private reasoning. After the final complete sentence, output %s alone on its own line. The marker ends transmission and is not shown to the player.
                """.formatted(FINAL_RESPONSE_STOP);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("locale", locale);
            payload.put("latestPlayerMessage", request.message());
            payload.put("recentConversation", conversationEvidence(request));
            Map<String, Object> validatedDecision = new LinkedHashMap<>();
            validatedDecision.put("outcome", decision.outcome().name().toLowerCase(Locale.ROOT));
            if (!decision.assistantMessage().isBlank()) {
                validatedDecision.put("legacyDecisionDraft", decision.assistantMessage());
            }
            validatedDecision.put("profile", evidenceReview.profileForAgent(decision.profile()));
            validatedDecision.put("selectedBggIds", decision.games().stream()
                    .map(game -> game.game().ranking().bggId())
                    .toList());
            if (decision.shortfall() != null) validatedDecision.put("shortfall", decision.shortfall());
            if (decision.clarification() != null) {
                validatedDecision.put("clarificationOptions", decision.clarification().options().stream()
                        .map(BoardGameRecommendationAgent.ClarificationOption::label)
                        .toList());
            }
            if (!state.finalResponseDecisionFacts.isEmpty()) {
                // A comparison may intentionally have no preferred winner. Preserve that explicit null in
                // the final voice payload instead of treating a sound, evidence-bounded non-choice as an error.
                validatedDecision.put("decisionFacts", new LinkedHashMap<>(state.finalResponseDecisionFacts));
            }
            if (decision.comparison() != null) {
                validatedDecision.put("comparison", Map.of(
                        "candidateBggIds", decision.comparison().candidates().stream()
                                .map(candidate -> candidate.game().ranking().bggId())
                                .toList(),
                        "subjects", decision.comparison().axes().stream()
                                .map(BoardGameRecommendationAgent.ComparisonAxis::subject)
                                .toList()));
            }
            payload.put("validatedDecision", validatedDecision);
            payload.put("allowedGames", state.finalResponseGameIds.stream()
                    .map(state.verified::get)
                    .filter(Objects::nonNull)
                    .limit(5)
                    .map(game -> actionExecutor.finalResponseGameObservation(
                            game,
                            state.research,
                            state.finalResponseEvidenceIds))
                    .toList());
            payload.put("sources", actionExecutor.sourceObservations(state.research.sources()));
            return List.of(Message.system(system), Message.user(json.writeValueAsString(payload)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation final response input could not be serialized", exception);
        }
    }

    private Optional<DirectConversationKind> directConversationTurn(ConversationRequest request) {
        String message = normalizedFastPathMessage(request.message());
        if (GREETING.matcher(message).matches()) {
            return Optional.of(DirectConversationKind.GREETING);
        }
        if (THANKS.matcher(message).matches()) return Optional.of(DirectConversationKind.THANKS);
        if (PAUSE.matcher(message).matches()) return Optional.of(DirectConversationKind.PAUSE);
        if (CAPABILITY.matcher(message).matches()) return Optional.of(DirectConversationKind.CAPABILITY);
        return Optional.empty();
    }

    private ConversationResponse naturalReplyResponse(
            RecommendationAgentState state,
            String locale,
            DirectConversationKind kind,
            String message) {
        state.actions.add("STREAMED_NATURAL_REPLY:" + kind.name());
        ConversationResponse response = new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_FAST_PATH,
                message,
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                List.of(),
                new HarnessTrace(state.modelCalls, 0, 0, false, state.actions, state.elapsedMs()),
                List.of(),
                null);
        logRun(response);
        return response;
    }

    private ConversationResponse streamExplicitTargetReply(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            ProgressTracker progress,
            Consumer<String> answerPartListener) {
        if (!model.configured(state.modelConfigurationOwner)) {
            progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
            progress.fail();
            return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
        }
        Game target = state.targetGameIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
        state.modelCalls++;
        try {
            NaturalReply reply = withinDeadline(
                    state,
                    () -> model.streamNaturalReply(
                            new NaturalReplyRequest(
                                    explicitTargetReplyMessages(request, target, locale),
                                    512,
                                    List.of(FINAL_RESPONSE_STOP)),
                            state.modelConfigurationOwner,
                            answerPartListener));
            if (reply.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT
                    || reply.text().isBlank()) {
                progress.fail();
                state.actions.add(reply.text().isBlank()
                        ? "EXACT_TARGET_REPLY_EMPTY"
                        : "EXACT_TARGET_REPLY_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "EXACT_TARGET_REPLY_INVALID");
            }
            state.actions.add("STREAMED_EXACT_TARGET_REPLY");
            progress.complete();
            ConversationResponse response = actionExecutor.publishExplicitTarget(state, locale, reply.text());
            logRun(response);
            return response;
        } catch (RunDeadlineExceeded exception) {
            progress.fail();
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
        } catch (RuntimeException exception) {
            progress.fail();
            LOGGER.warn("Recommendation exact-target reply stream failed ({})", exception.getClass().getSimpleName());
            state.actions.add("EXACT_TARGET_REPLY_FAILED");
            return unavailable(state, locale, "EXACT_TARGET_REPLY_FAILED");
        }
    }

    private List<Message> explicitTargetReplyMessages(
            ConversationRequest request,
            Game target,
            String locale) {
        String system = """
                You are RulePilot, a warm board-game conversation partner. The player has already selected one exact game and the application has verified its BGG identity. Acknowledge that exact choice naturally in the requested locale and point them to the card to continue into the rulebook or guide. Use the supplied verified title, but do not add gameplay, quality, fit, or experience claims. Do not mention routing, models, tools, policies, schemas, completion, or work status. Keep it to one or two short sentences. After the final sentence, output %s alone on its own line.
                """.formatted(FINAL_RESPONSE_STOP);
        try {
            Map<String, Object> verifiedTarget = new LinkedHashMap<>();
            verifiedTarget.put("bggId", target.ranking().bggId());
            verifiedTarget.put("name", target.ranking().sourceName());
            if (target.details().officialChineseName() != null
                    && !target.details().officialChineseName().isBlank()) {
                verifiedTarget.put("officialChineseName", target.details().officialChineseName());
            }
            return List.of(
                    Message.system(system),
                    Message.user(json.writeValueAsString(Map.of(
                            "locale", locale,
                            "currentMessage", request.message(),
                            "verifiedTarget", verifiedTarget))));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation exact-target reply could not be serialized", exception);
        }
    }

    private List<Message> naturalReplyMessages(
            ConversationRequest request,
            String locale,
            DirectConversationKind kind) {
        String turnContract = switch (kind) {
            case GREETING -> "Respond to the greeting and invite one natural next topic.";
            case THANKS -> "Acknowledge the thanks naturally and leave the conversation open.";
            case PAUSE -> "Acknowledge the pause. Do not recommend, search, or ask another question.";
            case CAPABILITY -> "Explain only these real capabilities: find and compare games using BGG data and attributed public sources, then continue a selected game into a cited rules guide and rule Q&A.";
        };
        String system = """
                You are RulePilot, a warm board-game conversation partner. This turn is already scoped to low-risk conversation and requires no external lookup. Reply directly in the requested locale. Keep it natural and concise, preserve useful conversational context, and do not mention routing, models, tools, policies, schemas, completion, or work status. Do not invent game facts or personal play experience. %s After the final sentence, output %s alone on its own line.
                """.formatted(turnContract, FINAL_RESPONSE_STOP);
        try {
            return List.of(
                    Message.system(system),
                    Message.user(json.writeValueAsString(Map.of(
                            "locale", locale,
                            "recentConversation", conversationEvidence(request),
                            "currentMessage", request.message()))));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation natural reply input could not be serialized", exception);
        }
    }

    private String normalizedFastPathMessage(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ")
                .replaceFirst("[\\p{P}\\p{S}]+$", "")
                .strip();
    }

    private int outputTokenBudget(RecommendationAgentState state) {
        return state.catalogCalls == 0 && state.webResearchCalls == 0
                ? ACTION_SELECTION_OUTPUT_TOKENS
                : EVIDENCE_RESPONSE_OUTPUT_TOKENS;
    }

    private ProgressStage progressStage(String action) {
        return switch (action) {
            case RESOLVE_TOOL -> ProgressStage.READING_GAME_DETAILS;
            case SEARCH_TOOL, BROWSE_TOOL -> ProgressStage.SEARCHING_BGG_CATALOG;
            case DISCOVER_TOOL -> ProgressStage.DISCOVERING_CANDIDATES;
            case LOOKUP_TOOL -> ProgressStage.VERIFYING_BGG_CANDIDATES;
            case RESEARCH_TOOL -> ProgressStage.RESEARCHING_GAME_FIT;
            case REPLY_TOOL, ASK_TOOL, COMPARE_TOOL, NO_MATCH_TOOL, RECOMMEND_TOOL ->
                ProgressStage.COMPOSING_RESPONSE;
            default -> ProgressStage.SELECTING_TOOLS;
        };
    }

    private ProgressAction progressAction(String action) {
        return switch (action) {
            case REPLY_TOOL -> ProgressAction.REPLY_TO_USER;
            case ASK_TOOL -> ProgressAction.ASK_USER;
            case RESOLVE_TOOL -> ProgressAction.RESOLVE_BGG_GAME;
            case SEARCH_TOOL -> ProgressAction.INSPECT_CANDIDATE_TITLES;
            case BROWSE_TOOL -> ProgressAction.BROWSE_BGG_CATALOG;
            case DISCOVER_TOOL -> ProgressAction.DISCOVER_PUBLIC_CANDIDATES;
            case LOOKUP_TOOL -> ProgressAction.LOOKUP_BGG_GAMES;
            case RESEARCH_TOOL -> ProgressAction.RESEARCH_GAME_FIT;
            case COMPARE_TOOL -> ProgressAction.COMPARE_CANDIDATES;
            case NO_MATCH_TOOL -> ProgressAction.REPORT_NO_MATCH;
            case RECOMMEND_TOOL -> ProgressAction.RECOMMEND_GAMES;
            default -> ProgressAction.CHOOSE_NEXT_ACTION;
        };
    }

    ConversationResponse unavailable(RecommendationAgentState state, String locale, String code) {
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
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (state.comparison != null) {
            state.comparison.axes().stream()
                    .flatMap(axis -> axis.cells().stream())
                    .map(BoardGameRecommendationAgent.ComparisonCell::observation)
                    .filter(Objects::nonNull)
                    .flatMap(observation -> observation.sourceIndexes().stream())
                    .forEach(cited::add);
        }
        if (cited.isEmpty()) return List.of();
        return state.research.sources().stream()
                .filter(source -> cited.contains(source.index()))
                .map(source -> new ResearchSource(
                        source.index(), source.title(), source.url(), source.domain()))
                .toList();
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
                    .map(game -> Map.of(
                            "bggId", game.bggId(),
                            "name", game.name(),
                            "originalName", game.originalName()))
                    .toList());
            if (!state.verified.isEmpty()) {
                data.put("restoredRunMemory", runMemory(state));
            }
            data.put("shownBggIds", request.shownBggIds());
            data.put("excludedBggIds", request.excludedBggIds());
            data.put("availableCapabilities", availableCapabilities(state));
            data.put("executionBudget", Map.of(
                    "maximumModelCalls", MAX_MODEL_CALLS,
                    "maximumDecisionModelCalls", MAX_DECISION_MODEL_CALLS,
                    "maximumActionCalls", MAX_ACTION_CALLS));
            data.put(
                    "goal",
                    "Continue the player's current conversation naturally through exactly one supplied action. "
                            + "Use reply_to_user only when no external work, state mutation, or new card is needed. "
                            + "A request to find a named game and continue into its rulebook or guide requires "
                            + "resolve_bgg_game with TARGET_GAME, not a prose confirmation.");
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private static String systemPromptV2() {
        return """
                You are RulePilot, a warm and capable board-game conversation partner. Read the complete recent conversation, give the latest explicit request priority over older turns, continue corrections and references in context, and answer in the player's locale and requested level of detail. Treat an explicit entity in the current turn as the current subject unless the player explicitly relates it to an earlier subject; resolve pronouns and elliptical continuations from recentConversation. A short standalone title supplied after an unresolved title keeps the earlier conversational role: a request for games like or similar to that title remains COMPARISON_REFERENCE, a request for that game itself or its guide remains TARGET_GAME, and an open question about it remains DISCUSSION_SUBJECT. Do not reinterpret a correction as a new target merely because it is the latest message. focusedBggId is present only for an explicit game-scoped UI action, never as a default interpretation of later free-form text. Every turn must use exactly one supplied action with valid JSON arguments; ordinary conversation, greetings, acknowledgements, reactions, and explicit pauses use reply_to_user without creating cards. A player who names a board game and asks to find or select it, open or read its rulebook, generate its guide, or continue into questions is asking for a selectable exact-title result: call resolve_bgg_game with TARGET_GAME. A prose confirmation does not complete that request. Escape JSON string content correctly. Never expose reasoning, schemas, tool names, or validation internals. Retrieval actions continue this run; reply, ask, compare, no-match, and recommend actions finish it.

                Speak like a decision partner at the table, not a task runner or completion report. Lead with the useful judgment or recommendation, then the reason and the one tradeoff that could change the choice. When it fits, offer a modest first-person opinion and acknowledge why the player's tradeoff is real; never invent personal play experience or flatter the player. Use the language of their actual group and planned session instead of making them translate their life into catalog taxonomy. Refer naturally to one or two high-signal details from the player's situation; do not recite every saved filter, narrate work performed, announce that analysis is complete, or turn the reply into a checklist. When the player corrects or critiques a suggestion, adapt visibly in the next answer instead of restating the old profile. For a newcomer who has not supplied domain vocabulary, prefer one plain question about the intended play situation over asking them to choose taxonomy. Ask at most one question, and only when its answer would materially change what you can recommend.

                Do not ask merely because a useful request is broad or the profile is empty: choose two or three meaningfully different directions and explain how to choose. Ask one easy question only when the missing answer is necessary to produce a valid slate, not just to narrow a large one; briefly explain its impact and offer direct options when useful. Store only explicit numeric/type constraints or a complete-group count supported by the cited user turn; result count and qualitative taste are not profile values, and later corrections replace earlier values. When the current message describes the speaker plus a complete enumerated group of companions and therefore implies one exact table size, include that count in the first action with evidenceClassification CONTEXTUAL_COMPLETE_GROUP. This is a visible, reversible working assumption, not a hard filter; do not emit it for an incomplete group or a requested card count. When compare, reply, or recommend finishes a turn that explicitly states or corrects a numeric/type constraint, include that update in the same action instead of merely discussing it, so the next turn receives the corrected profile.

                Choose a read only when the current turn actually needs information outside the conversation. knownGames are identity-only conversation memory, not permission to reload them pre-emptively; use lookup only if the current answer needs their BGG facts. Public candidate discovery and verified-game research are different capabilities: discover only to find new game identities for a selection criterion outside BGG; never use discovery to investigate a game already named or verified in runMemory. For current reception or player-reported experience about known verified candidates, call research once with every candidate being compared and one combined evidence question. A catalog browse is only a broad exploration or a filter over persisted numeric/type constraints. Generated-title inspection is for stable title hypotheses that need no external claim. Resolve an intact player-authored game title as a title. Every discovered title is verified through BGG before recommendation. A TARGET_GAME resolution returns verified identity and then exposes only the terminal card decision. Avoid repeated reads: discovery, research, and title inspection are each bounded, and runMemory is authoritative.

                After any supplied lookup, browse, discovery, or research action, the protocol requires another supplied action rather than bare assistant text. This keeps sourced claims attached to candidate-scoped observations and visible sources. Choose only from the actions supplied on that turn. For two or more compared candidates, finish through the supplied structured comparison action; after attributed multi-candidate research, an unstructured reply is unavailable. For selectable cards, finish through the supplied card action.

                Recommendation cards are an Agent decision, used only when the current goal asks for candidates or a selectable exact title. Recommend verified, eligible IDs and honor an explicit count. A terminal action is a compact decision contract, not a second prose-writing task: select IDs, select only the observations that the final voice may use, and stop. Do not draft the final answer inside a tool argument. The separate final voice receives the validated decision and only those selected observations, then streams the actual player-facing prose. Publisher wording supports only its literal premise, setting, components, or advertised feature; taxonomy remains a classification; attributed reports support only what they state. Never turn any of these into measured ease, pace, depth, tension, accessibility, interaction quality, or actual player experience. For comparisons, choose observed axes and a preferred candidate only when those observations justify a choice. Invalid terminal decisions are rejected for correction. Never output a Markdown table, narrate completion, or present an unselected candidate. Finish once evidence is sufficient.
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
        boolean unresolvedIdentityCanStillBeClarified = state.unresolvedPlayerTitle;
        boolean clarificationWouldMaskFailure = !unresolvedIdentityCanStillBeClarified
                && (state.clarificationBlockedByExecutionFailure
                        || state.titleInspectionAttempted && state.verified.isEmpty()
                        || state.catalogBrowseAttempted && state.verified.isEmpty()
                        || state.discoveryAttempted && state.verified.isEmpty());
        return actions.stream()
                .filter(action -> !state.unresolvedPlayerTitle
                        || RESOLVE_TOOL.equals(action.name())
                        || DISCOVER_TOOL.equals(action.name())
                        || ASK_TOOL.equals(action.name())
                        || REPLY_TOOL.equals(action.name()))
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
                .filter(action -> !state.researchAttempted || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> comparableIds.size() >= 2 || !COMPARE_TOOL.equals(action.name()))
                .filter(action -> !state.researchAttempted
                        || comparableIds.size() < 2
                        || !REPLY_TOOL.equals(action.name()))
                .filter(action -> !relaxableSubjects.isEmpty() || !NO_MATCH_TOOL.equals(action.name()))
                .filter(action -> !verifiedSlateAvailable
                        || REPLY_TOOL.equals(action.name())
                        || COMPARE_TOOL.equals(action.name())
                        || RECOMMEND_TOOL.equals(action.name())
                        || RESEARCH_TOOL.equals(action.name())
                                && state.webResearchAvailable
                                && !state.researchAttempted
                        || DISCOVER_TOOL.equals(action.name())
                                && state.webResearchAvailable
                                && !state.discoveryAttempted
                        || state.titleInspectionAttempted
                                && (BROWSE_TOOL.equals(action.name()) || DISCOVER_TOOL.equals(action.name())))
                .map(action -> RECOMMEND_TOOL.equals(action.name())
                        ? recommendationAction(
                                recommendationMinimumCount(state, recommendableIds),
                                recommendationMaximumCount(state, recommendableIds),
                                recommendableIds,
                                recommendableEvidenceIds(state, recommendableIds),
                                preferenceEvidenceIds,
                                availabilityShortfall(state, recommendableIds))
                        : COMPARE_TOOL.equals(action.name())
                                ? comparisonAction(
                                        comparableIds,
                                        availableComparisonSubjects(state, comparableIds),
                                        comparableEvidenceIds(state, comparableIds),
                                        preferenceEvidenceIds)
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
                properties.remove("contextualGroup");
            }
            if (schema.path("required") instanceof ArrayNode required) {
                for (int index = required.size() - 1; index >= 0; index--) {
                    if (Set.of("preferenceUpdates", "contextualGroup")
                            .contains(required.path(index).asText())) required.remove(index);
                }
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

    private List<String> availableComparisonSubjects(
            RecommendationAgentState state,
            List<Integer> comparableIds) {
        LinkedHashSet<String> subjects = new LinkedHashSet<>();
        comparableIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream())
                .map(CandidateObservation::attribute)
                .forEach(subjects::add);
        return List.copyOf(subjects);
    }

    private List<String> comparableEvidenceIds(
            RecommendationAgentState state,
            List<Integer> comparableIds) {
        return comparableIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream())
                .map(CandidateObservation::id)
                .distinct()
                .toList();
    }

    private List<String> recommendableEvidenceIds(
            RecommendationAgentState state,
            List<Integer> recommendableIds) {
        return recommendableIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream())
                .map(CandidateObservation::id)
                .distinct()
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
                .filter(game -> state.targetGameIds.contains(game.ranking().bggId())
                        || selector.eligible(game, state.profile))
                .map(game -> game.ranking().bggId())
                .toList();
    }

    private static List<ToolSpec> actions(int maximumResultCount, List<String> preferenceEvidenceIds) {
        String preferences = preferenceSchema(preferenceEvidenceIds);
        return List.of(
                new ToolSpec(
                        REPLY_TOOL,
                        "Terminal decision only when the latest goal needs no external work and no new selectable card. Do not write the player-facing answer here: the final model voice receives the conversation and this validated decision, then streams the answer once. referencedBggIds may include only already-verified games that the player is explicitly discussing; it grants the final voice access to those games' selected observations, but does not create cards. It is invalid when the player asks to find/select a title, open/read a rulebook, generate a guide, or continue into questions; resolve that title as TARGET_GAME instead. Never promise work for later when a supplied read can finish it now.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[]}"),
                new ToolSpec(
                        ASK_TOOL,
                        "Terminal clarification decision only when a missing player-owned answer is necessary to produce any valid slate. Put one intended question in question and two or three direct answers in options when useful; the validated question and options are then given to the final model voice for streamed wording. A broad actionable request or empty profile is not a reason to ask: recommend varied directions instead. Preserve stated hard numeric constraints and never ask after an execution failure.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"A natural locale-matched explanation of the missing decision followed by one useful clarification.\",\"minLength\":1},\"options\":{\"type\":\"array\",\"description\":\"Optional two or three direct answers.\",\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferenceUpdates\":"
                                + clarificationPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"question\"]}"),
                new ToolSpec(
                        RESOLVE_TOOL,
                        "Resolve one intact player-authored board-game title and declare why the conversation needs it. Use TARGET_GAME only when the player wants that game itself, its rulebook, guide, or questions. Use COMPARISON_REFERENCE when finding other games like/similar to it; the reference is verified but never selected. Use DISCUSSION_SUBJECT for prose discussion that does not select it, and IDENTITY_ONLY only when resolving identity is the whole goal. A short standalone correction inherits the unresolved title's earlier role from recentConversation; never promote it to TARGET_GAME merely because it is the latest message. Never translate, trim, or guess a title; people, awards, publishers, lists, and relationship phrases need public discovery. A verified TARGET_GAME returns its identity and makes recommend_games the only next action, so write the natural handoff there and publish its selectable card. Every other successful role returns verified facts so the still-open goal can continue.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160},\"purpose\":{\"type\":\"string\",\"enum\":[\"TARGET_GAME\",\"COMPARISON_REFERENCE\",\"DISCUSSION_SUBJECT\",\"IDENTITY_ONLY\"]},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"title\",\"purpose\"]}"),
                new ToolSpec(
                        SEARCH_TOOL,
                        "Inspect one to eight generated original/English candidate titles. Never include a player-named title. Results are BGG-verified.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"titles\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":120}},\"preferenceUpdates\":"
                                + readPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"titles\",\"preferenceUpdates\"]}"),
                new ToolSpec(
                        BROWSE_TOOL,
                        "Browse only for exploration or persisted numeric/type filters. types accepts only the schema's BGG game categories; COOPERATIVE, COMPETITIVE, and TEAM are interaction modes, never types. Omit types when broad variety is more useful. A complete group requires reversible exact playerCount; companions never prove type or interaction.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"preferenceUpdates\":"
                                + readPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"preferenceUpdates\"]}"),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Find new identities from public sources once when a criterion is outside BGG. types accepts only the schema's BGG game categories; interaction modes are never types. Research games already named or verified instead. Results are BGG-resolved in this read.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":3,\"maxLength\":300},\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"preferenceUpdates\":"
                                + readPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"query\",\"preferenceUpdates\"]}"),
                new ToolSpec(
                        LOOKUP_TOOL,
                        "Load BGG facts only for observed conversation-context IDs that do not yet have verified details.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"integer\",\"minimum\":1}}},\"required\":[\"bggIds\"]}"),
                new ToolSpec(
                        RESEARCH_TOOL,
                        "Research current reception or player-reported experience for already-verified games. For a comparison, include every compared bggId in this one bounded call and ask one combined question; after it returns, compare with the attributed R observations or leave unsupported qualities unknown.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"question\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":300}},\"required\":[\"bggIds\",\"question\"]}"),
                comparisonAction(List.of(), List.of(), List.of(), preferenceEvidenceIds),
                noMatchAction(List.of()),
                recommendationAction(1, maximumResultCount, List.of(), List.of(), preferenceEvidenceIds, null));
    }

    private static ToolSpec comparisonAction(
            List<Integer> comparableIds,
            List<String> availableSubjects,
            List<String> availableEvidenceIds,
            List<String> preferenceEvidenceIds) {
        String idConstraint = comparableIds.isEmpty()
                ? "\"minimum\":1"
                : "\"enum\":" + comparableIds;
        String evidenceConstraint = availableEvidenceIds.isEmpty()
                ? "\"minLength\":3,\"maxLength\":80"
                : "\"enum\":" + jsonArray(availableEvidenceIds);
        return new ToolSpec(
                COMPARE_TOOL,
                        "Terminal structural decision for comparing two to five verified conversation candidates on one to three axes. Use only when the player's current goal is a comparison and every candidate identity is already verified. This action selects the comparison, not its wording; do not draft prose. Available observed attributes in this turn are "
                        + availableSubjects
                        + ". Prefer reportedExperience only when attributed research was requested. Publisher descriptions support only their literal premise, setting, components, or advertised features. internalEvidenceIds must belong to the compared candidates and selected subjects; they are the complete factual allowance for the final streamed voice. Choose preferredBggId only when those observations justify a useful choice; otherwise use null. Leave unsupported requested qualities unknown. Persist an explicit current-turn numeric/type correction in preferenceUpdates in this same call, then stop. Never use this action to replace candidates.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"candidateBggIds\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"integer\","
                        + idConstraint
                        + "}},\"subjects\":{\"type\":\"array\",\"description\":\"One to three observation attribute names from runMemory. Unknown attributes remain visibly unknown instead of invalidating the comparison.\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferredBggId\":{\"description\":\"The one candidate you would choose from the selected observations, or null when the evidence does not support choosing.\",\"anyOf\":[{\"type\":\"integer\","
                        + idConstraint
                        + "},{\"type\":\"null\"}]},\"internalEvidenceIds\":{\"type\":\"array\",\"description\":\"The complete machine-only factual allowance for the final streamed comparison. Every ID must belong to a compared candidate and one of subjects.\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\","
                        + evidenceConstraint
                        + "}},\"preferenceUpdates\":"
                        + preferenceSchema(preferenceEvidenceIds)
                        + "},\"required\":[\"candidateBggIds\",\"subjects\",\"preferredBggId\",\"internalEvidenceIds\"]}");
    }

    private static ToolSpec noMatchAction(List<String> relaxableSubjects) {
        String subjectConstraint = relaxableSubjects.isEmpty()
                ? "\"minLength\":1,\"maxLength\":40"
                : "\"enum\":" + jsonArray(relaxableSubjects);
        return new ToolSpec(
                NO_MATCH_TOOL,
                "Finish with zero cards and select exactly one application-validated explicit constraint whose removal would unlock a verified candidate while every other explicit constraint stays unchanged. This action is the decision only; the final model voice streams the situation-specific wording. Do not invent a stock no-match sentence and do not claim that relaxing the constraint guarantees a recommendation.",
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
            List<String> availableEvidenceIds,
            List<String> preferenceEvidenceIds,
            AvailabilityShortfall shortfall) {
        String idConstraint = recommendableIds.isEmpty()
                ? "\"minimum\":1"
                : "\"enum\":" + recommendableIds;
        String selectionSchema = "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{"
                + "\"bggId\":{\"type\":\"integer\"," + idConstraint + "}"
                + "},\"required\":[\"bggId\"]}";
        String shortfallProperty = shortfall == null ? "" : ",\"shortfall\":" + shortfallSchema(shortfall);
        String required = shortfall == null
                ? "[\"selections\"]"
                : "[\"selections\",\"shortfall\"]";
        String selectionsProperty = "\"selections\":{\"type\":\"array\",\"description\":\"Choose the final card IDs first. Native JSON array of selection objects.\",\"minItems\":"
                + minimumResultCount
                + ",\"maxItems\":"
                + maximumResultCount
                + ",\"uniqueItems\":true,\"items\":"
                + selectionSchema
                + "}";
        String availabilityGuidance = shortfall == null
                ? ""
                : " The player requested " + shortfall.requestedCount()
                        + " cards, but exactly " + shortfall.availableCount()
                        + " hard-eligible IDs are available. Return every available ID once, never duplicate or pad. The UI renders the exact requested and available counts as structured data beside the cards; keep your own paragraph natural instead of reciting that interface label. Fill shortfall with the exact counts and concrete direct-reply relaxation options only for its allowed subjects; never promise that relaxing one guarantees another result.";
        String evidenceConstraint = availableEvidenceIds.isEmpty()
                ? "\"minLength\":3,\"maxLength\":80"
                : "\"enum\":" + jsonArray(availableEvidenceIds);
        return new ToolSpec(
                RECOMMEND_TOOL,
                "Terminal structural recommendation. Choose verified IDs that satisfy the player's explicit constraints and the requested count. Do not write prose: selections are the Agent's choice, and internalEvidenceIds are the complete factual allowance given to the separate final model voice. Include at least one literal observation for every selected game; prefer a publisher description when the player's request concerns premise, setting, components, or advertised features, and include only structured BGG facts for numeric eligibility. Taxonomy is classification only; it cannot prove ease, pace, depth, tension, accessibility, interaction quality, or player experience. The final voice streams the natural recommendation once this identity, constraint, count, and evidence-ownership contract passes. Return every available eligible ID plus the exact structured shortfall when required."
                        + availabilityGuidance,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + selectionsProperty
                        + ",\"internalEvidenceIds\":{\"type\":\"array\",\"description\":\"The complete machine-only factual allowance for the final streamed voice. IDs must belong to selected candidates and include at least one literal observation per selected game.\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\"," + evidenceConstraint + "}}"
                        + ",\"referenceBggIds\":{\"type\":\"array\",\"description\":\"Omit unless the player named a comparison game. Never put selected candidates here.\",\"maxItems\":2,\"items\":{\"type\":\"integer\",\"minimum\":1}}"
                        + shortfallProperty
                        + ",\"preferenceUpdates\":"
                        + preferenceSchema(preferenceEvidenceIds)
                        + "},\"required\":"
                        + required.replace("]", ",\"internalEvidenceIds\"]")
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
                + "\"reply\":{\"type\":\"string\",\"description\":\"A concrete first-person reply the player can select to relax only this bound. Do not claim it guarantees another match.\",\"minLength\":1}},"
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
                "Terminal decision only when this turn does not request new candidates and its goal is already answered. Do not draft prose here: the final voice receives the conversation and any referenced verified identities, then streams the reply once. Never narrate unfinished work or mention retrieved leads.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}}},"
                        + "\"required\":[]}");
    }

    private static String preferenceSchema(List<String> preferenceEvidenceIds) {
        return preferenceSchema(preferenceEvidenceIds, 1, "");
    }

    private static String readPreferenceSchema(List<String> preferenceEvidenceIds) {
        return preferenceSchema(
                preferenceEvidenceIds,
                0,
                "Return [] if none. A complete group requires reversible exact playerCount.");
    }

    private static String preferenceSchema(
            List<String> preferenceEvidenceIds,
            int minimumItems,
            String description) {
        String evidenceEnum = evidenceEnum(preferenceEvidenceIds);
        String descriptionProperty = description.isBlank()
                ? ""
                : "\"description\":\"" + description + "\",";
        return "{\"type\":\"array\"," + descriptionProperty + "\"minItems\":" + minimumItems + ",\"maxItems\":5,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"field\":{\"type\":\"string\",\"description\":\"Integer playerCount=exact; object=range.\",\"enum\":[\"players\",\"playerCount\",\"durationMinutes\",\"complexity\",\"type\",\"interaction\"]},"
                + "\"value\":{\"description\":\"Value; null clears a real limit.\",\"anyOf\":[{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"minimum\":{\"type\":[\"number\",\"null\"]},\"maximum\":{\"type\":[\"number\",\"null\"]}},\"required\":[\"minimum\",\"maximum\"]},{\"type\":\"null\"},{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\",\"COMPETITIVE\",\"COOPERATIVE\",\"TEAM\"]}]},"
                + "\"evidence\":{\"type\":\"string\",\"description\":\"Evidence ID from the current user-message only. A participant group may support only a reversible contextual player count; never cite game facts. Enum values must be affirmatively named, not inferred or rejected.\",\"enum\":"
                + evidenceEnum
                + "},\"evidenceClassification\":{\"type\":\"string\",\"description\":\"DIRECT=explicit hard value. CONTEXTUAL_COMPLETE_GROUP means the speaker plus every companion form an exact count; store it only as a reversible working assumption.\",\"enum\":[\"DIRECT\",\"CONTEXTUAL_COMPLETE_GROUP\"]}},"
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
        memory.put("observationLegend", Map.of(
                "M", "verified BGG structured metadata or bounded publisher description",
                "T", "BGG taxonomy label; use it only as a literal label, not as proof of another quality",
                "A", "attributed public report, limited to its literal claim",
                "R", "rulebook fact"));
        memory.put("profile", evidenceReview.profileForAgent(state.profile));
        putIfNotEmpty(memory, "contextualAssumptions", state.contextualPreferences.values().stream()
                .map(value -> Map.of(
                        "field", value.field(),
                        "value", value.value(),
                        "evidenceId", value.evidenceId()))
                .toList());
        putIfNotEmpty(memory, "candidateLeads", state.candidateNames.entrySet().stream()
                .filter(entry -> !state.verified.containsKey(entry.getKey()))
                .map(entry -> Map.of("bggId", entry.getKey(), "name", entry.getValue()))
                .toList());
        putIfNotEmpty(memory, "otherObservedBggIds", state.legalIds.stream()
                .filter(id -> !state.candidateNames.containsKey(id) && !state.verified.containsKey(id))
                .toList());
        memory.put("verifiedGames", state.verifiedForAgent().stream()
                .map(actionExecutor::gameObservation)
                .toList());
        memory.put("recommendableBggIds", recommendableIds(state));
        putIfNotEmpty(memory, "previouslyShownBggIds", state.previouslyShownIds.stream().toList());
        putIfNotEmpty(memory, "targetGameBggIds", state.targetGameIds.stream().toList());
        putIfNotEmpty(memory, "comparisonReferenceBggIds", state.comparisonReferenceIds.stream().toList());
        memory.put("referenceResolutionAttempts", state.referenceResolutionAttempts);
        if (state.namedGamePurpose != null) memory.put("namedGamePurpose", state.namedGamePurpose.name());
        putIfNotEmpty(memory, "researchEvidence", state.research.games().stream()
                .map(game -> Map.of(
                        "bggId", game.bggId(),
                        "observations", actionExecutor.researchObservations(game.bggId(), state.research).values().stream()
                                .map(item -> Map.of(
                                        "id", item.id(),
                                        "attribute", item.attribute(),
                                        "kind", item.kind().name(),
                                        "text", item.value(),
                                        "sourceIndexes", item.sourceIndexes()))
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
                "subjectiveFitResearch",
                        state.webResearchAvailable && !state.researchAttempted && !state.verified.isEmpty());
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
            object.put("remainingModelCalls", Math.max(0, MAX_DECISION_MODEL_CALLS - state.modelCalls));
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
                shown,
                List.of());
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
        String name = normalized(game.name(), true);
        String originalName = normalized(game.originalName(), true);
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

    private String normalized(String value, boolean allowBlank) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (!allowBlank && checked.isBlank()) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return checked;
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

    private void emitProgress(
            Consumer<ProgressUpdate> listener,
            ProgressStage stage,
            ProgressPhase phase,
            ProgressAction action,
            RecommendationAgentState state,
            long startedAt) {
        if (listener == null) return;
        try {
            int hardEligible = (int) state.verified.values().stream()
                    .filter(game -> selector.eligible(game, state.profile))
                    .count();
            listener.accept(new ProgressUpdate(
                    stage,
                    phase,
                    action,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    state.modelCalls,
                    state.modelCalls,
                    state.actionCalls,
                    state.catalogCalls,
                    state.webResearchCalls,
                    Math.max(state.candidateNames.size(), state.verified.size()),
                    state.verified.size(),
                    state.verified.size() - hardEligible,
                    state.sourceCount));
        } catch (RuntimeException exception) {
            LOGGER.debug("Recommendation progress listener stopped accepting updates");
        }
    }

    private final class ProgressTracker implements Consumer<ProgressStage> {
        private final Consumer<ProgressUpdate> listener;
        private final RecommendationAgentState state;
        private final long startedAt;
        private ProgressStage currentStage;
        private ProgressAction currentAction;

        private ProgressTracker(
                Consumer<ProgressUpdate> listener,
                RecommendationAgentState state,
                long startedAt) {
            this.listener = listener;
            this.state = state;
            this.startedAt = startedAt;
        }

        @Override
        public void accept(ProgressStage stage) {
            transition(stage, currentAction == null ? ProgressAction.CHOOSE_NEXT_ACTION : currentAction);
        }

        private void start(ProgressStage stage, ProgressAction action) {
            transition(stage, action);
        }

        private void transition(ProgressStage stage, ProgressAction action) {
            if (currentStage == stage && currentAction == action) return;
            complete();
            currentStage = stage;
            currentAction = action;
            emitProgress(listener, stage, ProgressPhase.STARTED, action, state, startedAt);
        }

        private void complete() {
            finish(ProgressPhase.COMPLETED);
        }

        private void retry() {
            finish(ProgressPhase.RETRYING);
        }

        private void fail() {
            finish(ProgressPhase.FAILED);
        }

        private void finish(ProgressPhase phase) {
            if (currentStage == null) return;
            emitProgress(listener, currentStage, phase, currentAction, state, startedAt);
            currentStage = null;
            currentAction = null;
        }
    }

    private enum DirectConversationKind {
        GREETING,
        THANKS,
        PAUSE,
        CAPABILITY
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
