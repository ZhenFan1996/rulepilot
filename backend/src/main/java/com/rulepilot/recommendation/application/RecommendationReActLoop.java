package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PROMPT_VERSION;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.FailureReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressAction;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ResearchSource;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TurnCheckpoint;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.RecommendationAgentState.DiscoveryPurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationPublication.PreparedPublication;
import com.rulepilot.shared.AsyncContextPropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

/** Owns the resource-bounded observe-decide-act loop and truthful degradation. */
final class RecommendationReActLoop {

    private static final int MAX_CATALOG_OFFSET =
            Integer.MAX_VALUE - BoardGameRecommendationCatalog.MAX_SEARCH_PAGE_SIZE;
    private static final Set<String> READ_ACTIONS = Set.of(
            RESOLVE_TOOL,
            BROWSE_TOOL,
            DISCOVER_TOOL,
            LOOKUP_TOOL,
            RESEARCH_TOOL);
    private static final Set<String> OBSERVED_ACTIONS = Set.of(
            UPDATE_PREFERENCES_TOOL,
            ASK_TOOL,
            RESOLVE_TOOL,
            BROWSE_TOOL,
            DISCOVER_TOOL,
            LOOKUP_TOOL,
            RESEARCH_TOOL,
            RECOMMEND_TOOL,
            COMPARE_TOOL,
            NO_MATCH_TOOL);

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BoardGameRecommendationModel model;
    private final BoardGameRecommendationTools tools;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationProperties properties;
    private final ObjectMapper json;
    private final ObjectMapper actionFingerprintJson;
    private final ExecutorService boundedCalls;
    private final long maximumRunMillis;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions actionExecutor;
    private final RecommendationPublication publication;
    private final ObservationRegistry observations;

    RecommendationReActLoop(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json) {
        this(model, tools, selector, properties, json, ObservationRegistry.NOOP);
    }

    RecommendationReActLoop(
            BoardGameRecommendationModel model,
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json,
            ObservationRegistry observations) {
        this.model = model;
        this.tools = tools;
        this.selector = selector;
        this.properties = properties;
        this.json = json;
        actionFingerprintJson = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        boundedCalls = AsyncContextPropagation.executorService(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recommendation-bounded-call-", 0).factory()));
        maximumRunMillis = properties.timeout().toMillis();
        evidenceReview = new RecommendationEvidenceReview(json, this);
        actionExecutor = new RecommendationActions(tools, selector, properties, json, evidenceReview, this);
        publication = new RecommendationPublication(selector, evidenceReview, actionExecutor, this, json);
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
    }

    void stopBoundedCalls() {
        boundedCalls.shutdownNow();
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener) {
        return converseValidated(
                validate(input),
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                null,
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
                answerPartListener,
                ignored -> {});
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
                null,
                ignored -> {});
    }

    ConversationResponse converseValidated(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        return converseValidated(
                request,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                null,
                checkpointListener);
    }

    ConversationResponse converseValidated(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        Observation workflow = Observation.createNotStarted("rulepilot.recommendation.workflow", observations)
                .contextualName("recommendation-react");
        return workflow.observe(() -> {
            try {
                ConversationResponse response = converseValidatedObserved(
                        request,
                        requestedLocale,
                        modelConfigurationOwner,
                        progressListener,
                        answerPartListener,
                        checkpointListener);
                workflow.lowCardinalityKeyValue(
                        "outcome", response.outcome().name().toLowerCase(Locale.ROOT));
                return response;
            } catch (RuntimeException | Error failure) {
                workflow.lowCardinalityKeyValue("outcome", "error");
                throw failure;
            }
        });
    }

    private ConversationResponse converseValidatedObserved(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        long startedAt = System.nanoTime();
        String locale = simplifiedChineseLocale(requestedLocale) ? "zh-CN" : "en";
        RecommendationAgentState state = new RecommendationAgentState(
                request,
                startedAt,
                modelConfigurationOwner,
                tools.webResearchConfigured(),
                properties.maxTokens());
        ProgressTracker progress = new ProgressTracker(progressListener, state, startedAt);
        try {
            return converseWithProgress(
                    request,
                    locale,
                    state,
                    progress,
                    answerPartListener,
                    checkpointListener);
        } catch (RuntimeException | Error failure) {
            progress.abort(failure);
            throw failure;
        }
    }

    private ConversationResponse converseWithProgress(
            ConversationRequest request,
            String locale,
            RecommendationAgentState state,
            ProgressTracker progress,
            Consumer<String> answerPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        progress.start(ProgressStage.UNDERSTANDING_REQUEST, ProgressAction.UNDERSTAND_REQUEST);
        progress.complete();
        if (!model.configured(state.modelConfigurationOwner)) {
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            progress.fail();
            return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
        }
        List<String> preferenceEvidenceIds = evidenceReview.preferenceEvidence(request).keySet().stream().toList();
        List<String> currentTurnEvidenceIds = preferenceEvidenceIds.isEmpty()
                ? List.of()
                : List.of(preferenceEvidenceIds.getLast());
        List<ToolSpec> actions = actions(preferenceEvidenceIds, currentTurnEvidenceIds);

        String input = agentInput(request, state, locale);
        List<Message> actionFoundation = List.of(
                Message.system(systemPromptV2()),
                Message.user(input));
        List<Message> messages = new ArrayList<>(actionFoundation);
        Map<String, SettledAction> settledActions = new LinkedHashMap<>();
        Set<String> rejectedIncompatibleActionSets = new LinkedHashSet<>();
        int stateEpoch = 0;
        while (true) {
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = availableActions(
                    state,
                    actions,
                    preferenceEvidenceIds,
                    currentTurnEvidenceIds);
            OperationObservation decisionObservation = startOperation(
                    "decision_model", "choose_next_action");
            long modelCallStartedAt = System.nanoTime();
            int estimatedModelInputTokens = estimatedModelInputTokens(messages, currentActions);
            RecommendationRunBudget.StopReason budgetStop = state.budget.beginModelStep(estimatedModelInputTokens);
            if (budgetStop != null) {
                decisionObservation.stop("resource_budget", false, null);
                progress.fail();
                return unavailableForBudget(state, locale, budgetStop);
            }
            state.modelCalls++;
            try {
                List<Message> turnMessages = messages;
                Request modelRequest = new Request(
                        turnMessages,
                        currentActions,
                        state.budget.remainingTokens(),
                        ToolChoice.AUTO);
                turn = withinDeadline(state, () -> answerPartListener == null
                        ? model.next(modelRequest, state.modelConfigurationOwner)
                        : model.stream(
                                modelRequest,
                                state.modelConfigurationOwner,
                                answerPartListener));
            } catch (RunInterrupted exception) {
                state.recordModelCallElapsed(modelCallStartedAt);
                decisionObservation.stop("interrupted", false, exception);
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RunDeadlineExceeded exception) {
                state.recordModelCallElapsed(modelCallStartedAt);
                decisionObservation.stop("deadline", false, exception);
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
                state.recordModelCallElapsed(modelCallStartedAt);
                decisionObservation.stop("error", false, exception);
                String failureCode = exception instanceof BoardGameRecommendationModel.ProtocolFailure protocol
                        ? "MODEL_PROTOCOL_FAILED:" + protocol.code()
                        : "MODEL_CALL_FAILED";
                LOGGER.warn(
                        "Recommendation ReAct turn failed (type={}, code={})",
                        exception.getClass().getSimpleName(),
                        failureCode);
                progress.fail();
                state.actions.add(failureCode);
                return unavailable(state, locale, failureCode);
            }
            state.recordModelCallElapsed(modelCallStartedAt);
            budgetStop = state.budget.completeModel(
                    turn.promptTokens(),
                    turn.completionTokens(),
                    estimatedTurnOutputTokens(turn));
            if (budgetStop != null) {
                decisionObservation.stop("resource_budget", false, null);
                progress.fail();
                return unavailableForBudget(state, locale, budgetStop);
            }
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                decisionObservation.stop("output_limit", false, null);
                progress.fail();
                state.actions.add("MODEL_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "MODEL_OUTPUT_TRUNCATED");
            }
            if (turn.toolCalls().isEmpty()) {
                if (turn.text().isBlank()) {
                    decisionObservation.stop("empty_response", false, null);
                    LOGGER.warn("Recommendation Agent turn returned neither a typed action nor natural text");
                    progress.fail();
                    state.actions.add("EMPTY_MODEL_RESPONSE");
                    return unavailable(state, locale, "EMPTY_MODEL_RESPONSE");
                }
                decisionObservation.stop("completed", false, null);
                progress.complete();
                state.actions.add("FINAL_ANSWER");
                return publishNaturalResponse(state, locale, turn.text(), progress);
            }
            if (turn.toolCalls().size() > 1) {
                String fingerprint = turn.toolCalls().stream()
                        .map(this::actionFingerprint)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("\n---\n"));
                decisionObservation.stop("rejected", false, null);
                LOGGER.warn(
                        "Recommendation ReAct turn returned {} parallel actions (textCharacters={})",
                        turn.toolCalls().size(),
                        turn.text().length());
                state.actionCalls++;
                if (!rejectedIncompatibleActionSets.add(fingerprint)) {
                    progress.fail();
                    state.actions.add("REPEATED_INCOMPATIBLE_ACTIONS");
                    return unavailable(state, locale, "REPEATED_INCOMPATIBLE_ACTIONS");
                }
                state.actions.add("REJECTED_INCOMPATIBLE_ACTIONS");
                progress.retry();
                String observation = contextualObservation(
                        error(
                                "INCOMPATIBLE_ACTIONS",
                                "Choose one action, observe its result, then decide whether another action is useful."),
                        state);
                compactPriorToolState(messages);
                messages.add(Message.assistant(turn.text(), turn.toolCalls()));
                turn.toolCalls().forEach(candidate -> messages.add(Message.tool(candidate, observation)));
                continue;
            }
            ToolCall call = turn.toolCalls().getFirst();
            decisionObservation.stop("completed", false, null);
            progress.complete();
            state.actionCalls++;
            String fingerprint = actionFingerprint(call);
            RecommendationActions.ActionOutcome outcome;
            boolean reused = false;
            OperationObservation actionObservation = startOperation(
                    "typed_action", observedAction(call.name()));
            SettledAction settled = settledActions.get(fingerprint);
            if (settled != null && settled.stateEpoch() == stateEpoch) {
                if (settled.outcome().deterministicContractRejection()) {
                    String rejectionCode = settled.outcome().rejectionCode();
                    actionObservation.stop("repeated_contract_error", false, null);
                    state.actions.add("REUSED_ACTION_ERROR");
                    state.actions.add("REPEATED_DETERMINISTIC_ACTION:" + rejectionCode);
                    progress.fail();
                    return unavailable(
                            state,
                            locale,
                            "REPEATED_DETERMINISTIC_ACTION:" + rejectionCode);
                }
                if (settled.reusedObservation()) {
                    actionObservation.stop("no_progress", false, null);
                    state.actions.add("NO_PROGRESS:REPEATED_READ_OBSERVATION");
                    progress.fail();
                    return unavailable(
                            state,
                            locale,
                            "NO_PROGRESS:REPEATED_READ_OBSERVATION");
                }
                reused = true;
                outcome = settled.outcome();
                settledActions.put(fingerprint, settled.afterObservationReuse());
                actionObservation.stop("reused", false, null);
                state.actions.add("REUSED_READ_OBSERVATION");
            } else if (currentActions.stream().noneMatch(action -> action.name().equals(call.name()))) {
                state.actions.add("REJECTED_UNAVAILABLE_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.rejectedContract(
                        error(
                                "ACTION_NOT_AVAILABLE",
                                "That capability is not available in this turn. Choose one action from the supplied list."),
                        "ACTION_NOT_AVAILABLE");
                actionObservation.stop("rejected", false, null);
            } else {
                budgetStop = state.budget.beginToolCall(RecommendationRunBudget.estimateTokens(call.argumentsJson()));
                if (budgetStop != null) {
                    actionObservation.stop("resource_budget", false, null);
                    progress.fail();
                    return unavailableForBudget(state, locale, budgetStop);
                }
                try {
                    outcome = actionExecutor.execute(
                            call,
                            state,
                            request,
                            locale,
                            (stage, focus) -> progress.start(stage, progressAction(call.name()), focus));
                    if (outcome.publicationArgumentsJson() != null) {
                        try {
                            PreparedPublication prepared = publication.prepare(
                                    state,
                                    outcome.publicationArgumentsJson());
                            actionObservation.stop("completed", false, null);
                            progress.complete();
                            return publishRecommendationWithinBoundary(
                                    state,
                                    locale,
                                    prepared,
                                    progress);
                        } catch (RecommendationPublication.InvalidPublication failure) {
                            state.actions.add("REJECTED_ACTION:" + failure.code().name());
                            outcome = RecommendationActions.ActionOutcome.rejectedContract(
                                    recommendationRepairObservation(
                                            call,
                                            currentActions,
                                            state,
                                            failure),
                                    failure.code().name());
                            actionObservation.stop("rejected", false, null);
                        }
                    } else {
                        actionObservation.stop(
                                outcome.rejected() ? "rejected" : "completed",
                                false,
                                null);
                    }
                } catch (RunInterrupted exception) {
                    actionObservation.stop("interrupted", false, exception);
                    progress.fail();
                    state.actions.add("RUN_DEADLINE_EXCEEDED");
                    return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
                } catch (RunDeadlineExceeded exception) {
                    actionObservation.stop("deadline", false, exception);
                    progress.fail();
                    state.actions.add("RUN_DEADLINE_EXCEEDED");
                    return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
                } catch (RuntimeException exception) {
                    actionObservation.stop("error", false, exception);
                    throw exception;
                }
            }
            if (!reused && !outcome.rejected()) {
                stateEpoch++;
            }
            if (!reused
                    && (outcome.deterministicContractRejection()
                            || (!outcome.rejected() && READ_ACTIONS.contains(call.name())))) {
                settledActions.put(fingerprint, new SettledAction(outcome, stateEpoch));
            }
            if (!reused && outcome.settledRead()) {
                checkpointListener.accept(new TurnCheckpoint(state.profile, state.verifiedForAgent()));
            }
            if (outcome.response() != null) {
                progress.complete();
                return publishValidatedResponse(
                        progress,
                        outcome.response());
            }
            if (outcome.rejected()) {
                progress.retry();
            } else {
                progress.complete();
            }
            String observation = contextualObservation(
                    contractObservation(call, outcome, currentActions),
                    state);
            budgetStop = state.budget.completeToolCall(RecommendationRunBudget.estimateTokens(observation));
            if (budgetStop != null) {
                progress.fail();
                return unavailableForBudget(state, locale, budgetStop);
            }
            compactPriorToolState(messages);
            messages.add(Message.assistant(turn.text(), call));
            messages.add(Message.tool(call, observation));
        }
    }

    String actionFingerprint(ToolCall call) {
        try {
            JsonNode arguments = actionFingerprintJson.readTree(call.argumentsJson());
            return call.name() + "\n" + actionFingerprintJson.writeValueAsString(canonicalJson(arguments));
        } catch (JsonProcessingException exception) {
            // Invalid JSON still needs an exact retry identity so the same rejected payload can be reused;
            // successful actions use their typed JSON value and are insensitive to object-key order or whitespace.
            return call.name() + "\n" + call.argumentsJson();
        }
    }

    private String contractObservation(
            ToolCall call,
            RecommendationActions.ActionOutcome outcome,
            List<ToolSpec> currentActions) {
        if (!outcome.deterministicContractRejection()) return outcome.observation();
        ToolSpec contract = currentActions.stream()
                .filter(action -> action.name().equals(call.name()))
                .findFirst()
                .orElse(null);
        if (contract == null) return outcome.observation();
        try {
            JsonNode parsed = json.readTree(outcome.observation());
            if (!(parsed instanceof ObjectNode object) || object.has("replacementContract")) {
                return outcome.observation();
            }
            ObjectNode replacement = object.putObject("replacementContract");
            replacement.put("action", contract.name());
            replacement.set("toolSchema", json.readTree(contract.inputSchema()));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation action contract could not be serialized", exception);
        }
    }

    private String recommendationRepairObservation(
            ToolCall call,
            List<ToolSpec> currentActions,
            RecommendationAgentState state,
            RecommendationPublication.InvalidPublication failure) {
        ToolSpec contract = currentActions.stream()
                .filter(action -> RECOMMEND_TOOL.equals(action.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("recommendation action contract is unavailable"));

        Map<String, Object> submittedToolCall = new LinkedHashMap<>();
        submittedToolCall.put("id", call.id());
        submittedToolCall.put("name", call.name());
        submittedToolCall.put("argumentsJson", call.argumentsJson());
        try {
            JsonNode arguments = actionFingerprintJson.readTree(call.argumentsJson());
            if (arguments != null) submittedToolCall.put("arguments", arguments);
        } catch (JsonProcessingException ignored) {
            // The exact raw arguments remain available when JSON itself is the deepest failure.
        }

        Map<String, Object> deepestError = new LinkedHashMap<>();
        deepestError.put("code", failure.code().name());
        deepestError.put("path", failure.path());
        deepestError.put("details", failure.details());

        List<Integer> allowedCandidateIds = pendingPublicationIds(state);
        Map<String, List<String>> allowedEvidenceIdsByBggId = new LinkedHashMap<>();
        for (Integer bggId : allowedCandidateIds) {
            Game game = state.verified.get(bggId);
            if (game == null) continue;
            allowedEvidenceIdsByBggId.put(
                    String.valueOf(bggId),
                    actionExecutor.narrativeObservations(game, state.research).keySet().stream().toList());
        }

        Map<String, Object> replacementContract = new LinkedHashMap<>();
        try {
            replacementContract.put("toolSchema", json.readTree(contract.inputSchema()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation action schema is invalid", exception);
        }
        replacementContract.put("allowedCandidateBggIds", allowedCandidateIds);
        replacementContract.put("allowedEvidenceIdsByBggId", allowedEvidenceIdsByBggId);

        return error(
                failure.code().name(),
                "The recommendation was not published. Review deepestError and submit one complete replacement recommend_games JSON matching replacementContract, or choose another useful available action.",
                Map.of(
                        "submittedToolCall", submittedToolCall,
                        "deepestError", deepestError,
                        "replacementContract", replacementContract));
    }

    private JsonNode canonicalJson(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = actionFingerprintJson.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> canonical.set(field, canonicalJson(value.path(field))));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = actionFingerprintJson.createArrayNode();
            value.forEach(element -> canonical.add(canonicalJson(element)));
            return canonical;
        }
        return value.deepCopy();
    }

    private ConversationResponse publishValidatedResponse(
            ProgressTracker progress,
            ConversationResponse decision) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.REPLY_TO_USER);
        if (decision.assistantMessage().isBlank()) {
            progress.fail();
            throw new IllegalStateException("validated recommendation decision omitted its player reply");
        }
        progress.complete();
        logRun(decision);
        return decision;
    }

    private ConversationResponse publishNaturalResponse(
            RecommendationAgentState state,
            String locale,
            String naturalText,
            ProgressTracker progress) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.REPLY_TO_USER);
        ConversationResponse response = new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                naturalText,
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                naturalResponseSources(state),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        state.actions,
                        state.elapsedMs(),
                        state.modelCallElapsedMs),
                List.of(),
                null,
                null);
        progress.complete();
        logRun(response);
        return response;
    }

    private List<ResearchSource> naturalResponseSources(RecommendationAgentState state) {
        Map<String, ResearchSource> sources = new LinkedHashMap<>();
        int nextIndex = 1;
        for (Source source : state.research.sources()) {
            if (sources.containsKey(source.url())) continue;
            sources.put(source.url(), new ResearchSource(
                    nextIndex++, source.title(), source.url(), source.domain()));
        }
        for (Source source : state.publicContextSources) {
            if (sources.containsKey(source.url())) continue;
            sources.put(source.url(), new ResearchSource(
                    nextIndex++, source.title(), source.url(), source.domain()));
        }
        return List.copyOf(sources.values());
    }

    private ConversationResponse publishRecommendationWithinBoundary(
            RecommendationAgentState state,
            String locale,
            PreparedPublication prepared,
            ProgressTracker progress) {
        try {
            return publishRecommendation(
                    state,
                    locale,
                    prepared,
                    progress);
        } catch (RuntimeException exception) {
            progress.fail();
            String code = "PUBLICATION_PROJECTION_FAILED";
            state.actions.add("PUBLICATION_FAILED:" + code);
            LOGGER.warn("Recommendation publication failed ({})", code);
            return unavailable(state, locale, code);
        }
    }

    private ConversationResponse publishRecommendation(
            RecommendationAgentState state,
            String locale,
            PreparedPublication prepared,
            ProgressTracker progress) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.RECOMMEND_GAMES);
        ConversationResponse response = publication.publish(state, prepared, locale);
        progress.complete();
        logRun(response);
        return response;
    }

    private ProgressAction progressAction(String action) {
        return switch (action) {
            case UPDATE_PREFERENCES_TOOL -> ProgressAction.CHOOSE_NEXT_ACTION;
            case ASK_TOOL -> ProgressAction.ASK_USER;
            case RESOLVE_TOOL -> ProgressAction.RESOLVE_BGG_GAME;
            case BROWSE_TOOL -> ProgressAction.BROWSE_BGG_CATALOG;
            case DISCOVER_TOOL -> ProgressAction.DISCOVER_PUBLIC_CANDIDATES;
            case LOOKUP_TOOL -> ProgressAction.LOOKUP_BGG_GAMES;
            case RESEARCH_TOOL -> ProgressAction.RESEARCH_GAME_FIT;
            case RECOMMEND_TOOL -> ProgressAction.RECOMMEND_GAMES;
            case COMPARE_TOOL -> ProgressAction.COMPARE_CANDIDATES;
            case NO_MATCH_TOOL -> ProgressAction.REPORT_NO_MATCH;
            default -> ProgressAction.CHOOSE_NEXT_ACTION;
        };
    }

    private int estimatedModelInputTokens(List<Message> messages, List<ToolSpec> tools) {
        int tokens = 0;
        for (Message message : messages) {
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(message.content()));
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(message.toolCallId()));
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(message.toolName()));
            for (ToolCall call : message.toolCalls()) {
                tokens = RecommendationRunBudget.saturatedAdd(
                        tokens, RecommendationRunBudget.estimateTokens(call.name()));
                tokens = RecommendationRunBudget.saturatedAdd(
                        tokens, RecommendationRunBudget.estimateTokens(call.argumentsJson()));
            }
        }
        for (ToolSpec tool : tools) {
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(tool.name()));
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(tool.description()));
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(tool.inputSchema()));
        }
        return Math.max(1, tokens);
    }

    private int estimatedTurnOutputTokens(BoardGameRecommendationModel.Turn turn) {
        int tokens = RecommendationRunBudget.estimateTokens(turn.text());
        for (ToolCall call : turn.toolCalls()) {
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(call.name()));
            tokens = RecommendationRunBudget.saturatedAdd(
                    tokens, RecommendationRunBudget.estimateTokens(call.argumentsJson()));
        }
        return tokens;
    }

    ConversationResponse unavailable(RecommendationAgentState state, String locale, String code) {
        state.actions.add("UNAVAILABLE:" + code);
        FailureReason reason = failureReason(state, code);
        ConversationResponse response = new ConversationResponse(
                Outcome.UNAVAILABLE,
                DecisionMode.MODEL_ASSISTED,
                playerFacingFailureMessage(reason, locale, code),
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
                        state.elapsedMs(),
                        state.modelCallElapsedMs,
                        reason),
                List.of(),
                null);
        logRun(response);
        return response;
    }

    private ConversationResponse unavailableForBudget(
            RecommendationAgentState state,
            String locale,
            RecommendationRunBudget.StopReason stopReason) {
        String code = switch (stopReason) {
            case STEP_BUDGET -> "RUN_STEP_BUDGET_EXCEEDED";
            case TOOL_BUDGET -> "RUN_TOOL_BUDGET_EXCEEDED";
            case TOKEN_BUDGET -> "RUN_TOKEN_BUDGET_EXCEEDED";
        };
        state.actions.add(code);
        return unavailable(state, locale, code);
    }

    private FailureReason failureReason(RecommendationAgentState state, String code) {
        if ("RUN_DEADLINE_EXCEEDED".equals(code)) return FailureReason.TIME_LIMIT;
        if (code.startsWith("RUN_") && code.endsWith("_BUDGET_EXCEEDED")) {
            return FailureReason.RESOURCE_BUDGET_EXHAUSTED;
        }
        if ("MODEL_NOT_CONFIGURED".equals(code)) return FailureReason.MODEL_NOT_CONFIGURED;
        if ("MODEL_CALL_FAILED".equals(code)) return FailureReason.PROVIDER_CALL_FAILED;
        if (code.startsWith("MODEL_PROTOCOL_FAILED:")) return FailureReason.PROVIDER_PROTOCOL_INVALID;
        if ("MODEL_OUTPUT_TRUNCATED".equals(code)) return FailureReason.PROVIDER_OUTPUT_TRUNCATED;
        if ("EMPTY_MODEL_RESPONSE".equals(code)) return FailureReason.EMPTY_MODEL_RESPONSE;
        if ("REPEATED_INCOMPATIBLE_ACTIONS".equals(code)) {
            return FailureReason.REPEATED_INCOMPATIBLE_ACTIONS;
        }
        if (code.startsWith("REPEATED_DETERMINISTIC_ACTION:")) {
            return FailureReason.REPEATED_INVALID_ACTION;
        }
        if (state.actions.stream().anyMatch(action -> action.startsWith("PUBLICATION_FAILED:"))) {
            return FailureReason.PUBLICATION_REJECTED;
        }
        return FailureReason.SERVICE_FAILURE;
    }

    private String playerFacingFailureMessage(FailureReason reason, String locale, String code) {
        boolean zh = chinese(locale);
        if ("NO_PROGRESS:REPEATED_READ_OBSERVATION".equals(code)) {
            return zh
                    ? "模型在同一状态下再次重复了已经复用过的读取结果。本轮已停止，避免继续做没有进展的重复读取。"
                    : "The model repeated a read result that had already been reused in the same state. The turn stopped instead of continuing a no-progress read loop.";
        }
        return switch (reason) {
            case TIME_LIMIT -> zh
                    ? "本轮总时限已用完，模型或检索尚未返回可发布的完整结果。已核对的会话信息仍然保留，可以直接重试。"
                    : "This turn reached its total time limit before the model or retrieval returned a complete publishable result. Verified conversation context is still saved, so you can retry.";
            case RESOURCE_BUDGET_EXHAUSTED -> zh
                    ? "本轮在得到可发布结果前用完了安全资源预算。请求与已核对的会话信息仍然保留；修改问题以缩小范围，或稍后在新的上下文中重试。"
                    : "This turn exhausted its safety resource budget before producing a publishable result. The request and verified context remain saved; narrow the question or retry later in a fresh context.";
            case MODEL_NOT_CONFIGURED -> zh
                    ? "当前账号没有可用的推荐模型配置。本轮没有调用检索，也没有发布临时结果。"
                    : "This account has no available recommendation model configuration. No retrieval ran and no provisional result was published.";
            case PROVIDER_CALL_FAILED -> zh
                    ? "推荐模型服务连接失败或提前中断；这不是“没有匹配候选”。已核对的会话信息仍然保留，可以直接重试。"
                    : "The recommendation model request failed or disconnected; this does not mean that no candidate matched. Verified conversation context is still saved, so you can retry.";
            case PROVIDER_PROTOCOL_INVALID -> zh
                    ? "模型返回的工具协议无法安全解析，因此没有执行不确定的动作，也没有发布草稿。"
                    : "The model returned a tool protocol that could not be parsed safely, so no uncertain action ran and no draft was published.";
            case PROVIDER_OUTPUT_TRUNCATED -> zh
                    ? "模型输出达到长度上限，回答或工具参数不完整，因此本轮没有发布。"
                    : "The model reached its output limit, leaving the answer or action arguments incomplete, so this turn was not published.";
            case EMPTY_MODEL_RESPONSE -> zh
                    ? "模型这次既没有返回自然回答，也没有选择工具。本轮没有自动补写或强制追加一次调用。"
                    : "The model returned neither a natural answer nor an action. The application did not fabricate text or force another call.";
            case REPEATED_INCOMPATIBLE_ACTIONS -> zh
                    ? "模型在收到逐步执行提示后，仍重复提交同一组并行动作。为避免跳过工具结果，本轮已停止。"
                    : "After being told to act step by step, the model repeated the same parallel action set. The turn stopped rather than skipping tool observations.";
            case REPEATED_INVALID_ACTION -> zh
                    ? "模型看到明确的参数校验错误后，仍重复完全相同的无效动作。本轮已停止，避免无意义循环。"
                    : "After receiving a precise parameter error, the model repeated the identical invalid action. The turn stopped to avoid a pointless loop.";
            case PUBLICATION_REJECTED -> zh
                    ? "最终候选、证据归属或完整回复没有通过发布校验。为避免把未经支持的内容显示成推荐，本轮未发布；已核对事实仍保留。"
                    : "The final candidates, evidence ownership, or complete reply failed publication validation. Nothing unsupported was shown as a recommendation, and verified facts remain saved.";
            case SERVICE_FAILURE -> zh
                    ? "推荐服务在本轮遇到无法归类的运行故障，未写入未完成结果。请求和已核对会话信息仍然保留。"
                    : "The recommendation service hit an unclassified runtime failure and did not commit an incomplete result. The request and verified conversation context remain saved.";
        };
    }

    void logRun(ConversationResponse response) {
        LOGGER.info(
                "Recommendation ReAct run completed: promptVersion={}, outcome={}, totalElapsedMs={}, modelCalls={}, modelCallElapsedMs={}, catalogCalls={}, webResearchCalls={}, candidatesEvaluated={}, actions={}",
                PROMPT_VERSION,
                response.outcome(),
                response.harness().totalElapsedMs(),
                response.harness().modelCalls(),
                response.harness().modelCallElapsedMs(),
                response.harness().catalogCalls(),
                response.harness().webResearchCalls(),
                response.candidatesEvaluated(),
                response.harness().actions());
    }

    List<ResearchSource> responseSources(RecommendationAgentState state, List<RecommendedGame> games) {
        return responseSources(state, games, state.finalResponseEvidenceIds);
    }

    List<ResearchSource> responseSources(
            RecommendationAgentState state,
            List<RecommendedGame> games,
            Set<String> finalResponseEvidenceIds) {
        Set<Integer> cited = games.stream()
                .map(RecommendedGame::game)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream())
                .filter(observation -> finalResponseEvidenceIds.contains(observation.id()))
                .flatMap(observation -> observation.sourceIndexes().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (state.comparison != null) {
            state.comparison.axes().stream()
                    .flatMap(axis -> axis.cells().stream())
                    .map(BoardGameRecommendationAgent.ComparisonCell::observation)
                    .filter(Objects::nonNull)
                    .flatMap(observation -> observation.sourceIndexes().stream())
                    .forEach(cited::add);
        }
        List<ResearchSource> result = new ArrayList<>(state.research.sources().stream()
                .filter(source -> cited.contains(source.index()))
                .map(source -> new ResearchSource(
                        source.index(), source.title(), source.url(), source.domain()))
                .toList());
        Set<Integer> publicCitations = state.publicContextEvidence.values().stream()
                .filter(evidence -> state.finalResponsePublicEvidenceIds.contains(evidence.id()))
                .flatMap(evidence -> evidence.sourceIndexes().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> seenUrls = result.stream()
                .map(ResearchSource::url)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int nextIndex = result.stream().mapToInt(ResearchSource::index).max().orElse(0) + 1;
        for (var source : state.publicContextSources) {
            if (!publicCitations.contains(source.index()) || !seenUrls.add(source.url())) continue;
            result.add(new ResearchSource(
                    nextIndex++,
                    source.title(),
                    source.url(),
                    source.domain()));
        }
        return List.copyOf(result);
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
            putIfNotEmpty(data, "contextualAssumptions", state.contextualPreferences.values().stream()
                    .map(value -> Map.of(
                            "field", value.field(),
                            "value", value.value(),
                            "evidenceId", value.evidenceId()))
                    .toList());
            data.put("recentConversation", conversationEvidence(request));
            if (request.focusedBggId() != null) data.put("focusedBggId", request.focusedBggId());
            putIfNotEmpty(data, "knownGames", request.knownGames().stream()
                    .map(game -> Map.of(
                            "bggId", game.bggId(),
                            "name", game.name(),
                            "originalName", game.originalName()))
                    .toList());
            if (!state.verified.isEmpty() || state.hasVerifiedPublicContext()) {
                data.put("restoredTurnState", turnState(state));
            }
            putIfNotEmpty(data, "shownBggIds", request.shownBggIds());
            putIfNotEmpty(data, "excludedBggIds", request.excludedBggIds());
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private static String systemPromptV2() {
        return """
                You are RulePilot, a knowledgeable, natural board-game companion. Treat recentConversation as the complete request, honor its latest correction, and answer in the player's language.

                Use a supplied typed action only when the turn needs its machine-owned state change, retrieval, or structured UI artifact. Otherwise answer the player directly in natural prose. After every action result, either choose the next useful action or finish with the complete answer; never call an action merely to wrap prose. Typed arguments and cited user evidence own routing and memory, while direct prose is player-facing output only. Retrieve game facts instead of guessing. Ask at most one question, and only when one missing player choice materially changes the answer.

                Omit requestedCount when the player did not state a count; the host applies the product default. For an explicitly stated count, set requestedCount and cite that current user turn's U id in evidence; never reuse an older turn's count. limit is only retrieval size. preferenceUpdates is one evidence-scoped patch for player-stated preferences: playerCount, durationMinutes, and complexity are numeric; type is a BGG product class; interaction is COMPETITIVE, COOPERATIVE, or TEAM. Never save clarification options or inferred mood. Range patches preserve omitted bounds. Cited numbers are DIRECT; INFERRED_GROUP_MEMBER_COUNT means counting stated members.

                Prefer browse_bgg_catalog for cards and BGG facts; use resolve_bgg_game for an exact player-written title. Use public discovery when an uncertain or current relationship involving a person, event, organization, game, or other entity needs attributed evidence. Its result returns atomic public context and optional title leads. After every observation, decide whether it is sufficient, another genuinely relevant capability can add the missing evidence, or the truthful answer is that the evidence is unavailable. Never repeat the same read or use an unrelated BGG read merely because public search failed. SELECTABLE_CARDS title leads still require BGG verification before cards. Use textQuery for concepts rather than inventing taxonomy.

                Card-producing reads never publish. When the player needs recommendation cards, use recommend_games after a verified observation: choose only its enumerated candidates, write one complete natural playerReply, bind any candidate-specific factual wording with internal playerReplyEvidenceIds, and write each card's why/tradeoff only from that candidate's enumerated evidence. A generic conversational lead may use no evidence id. Use compare_candidates only when the player needs the structured comparison artifact. Never expose hidden reasoning, schemas, internal ids, evidence ids, or workflow.
                """;
    }

    private List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds) {
        List<Integer> pendingPublicationIds = pendingPublicationIds(state);
        List<Integer> comparableIds = comparableIds(state);
        List<String> relaxableSubjects = relaxableSubjects(state);
        boolean unresolvedIdentityCanStillBeClarified = state.unresolvedPlayerTitle;
        boolean clarificationWouldMaskFailure = !unresolvedIdentityCanStillBeClarified
                && state.clarificationBlockedByExecutionFailure;
        List<ToolSpec> available = actions.stream()
                .filter(action -> !RECOMMEND_TOOL.equals(action.name()))
                .filter(action -> !clarificationWouldMaskFailure || !ASK_TOOL.equals(action.name()))
                .filter(action -> state.webResearchAvailable
                        || !isDiscoveryAction(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verified.isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> state.legalIds.stream().anyMatch(id -> !state.verified.containsKey(id))
                        || !LOOKUP_TOOL.equals(action.name()))
                .filter(action -> comparableIds.size() >= 2 || !COMPARE_TOOL.equals(action.name()))
                .filter(action -> !relaxableSubjects.isEmpty() || !NO_MATCH_TOOL.equals(action.name()))
                .map(action -> BROWSE_TOOL.equals(action.name())
                                ? catalogAction(preferenceEvidenceIds, currentTurnEvidenceIds)
                        : COMPARE_TOOL.equals(action.name())
                                ? comparisonAction(
                                        comparableIds,
                                        availableComparisonSubjects(state, comparableIds),
                                        comparableEvidenceIds(state, comparableIds),
                                        preferenceEvidenceIds)
                        : NO_MATCH_TOOL.equals(action.name())
                                ? noMatchAction(relaxableSubjects)
                        : action)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!pendingPublicationIds.isEmpty()) {
            available.add(recommendationAction(state, pendingPublicationIds));
        }
        return List.copyOf(available);
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
        return List.copyOf(actionable);
    }

    List<Integer> recommendableIds(RecommendationAgentState state) {
        return state.verified.values().stream()
                .filter(game -> !state.excludedIds.contains(game.ranking().bggId()))
                .filter(game -> !state.previouslyShownIds.contains(game.ranking().bggId())
                        || state.targetGameIds.contains(game.ranking().bggId()))
                .filter(game -> !state.comparisonReferenceIds.contains(game.ranking().bggId()))
                .filter(game -> state.titleConstraint == null || state.titleConstraint.matches(game))
                .filter(game -> state.targetGameIds.contains(game.ranking().bggId())
                        || selector.eligible(game, state.profile))
                .map(game -> game.ranking().bggId())
                .toList();
    }

    private List<Integer> pendingPublicationIds(RecommendationAgentState state) {
        PublicationSeed pending = state.pendingPublicationSeed;
        if (pending == null) return List.of();
        Set<Integer> recommendable = new LinkedHashSet<>(recommendableIds(state));
        return pending.candidateBggIds().stream()
                .filter(recommendable::contains)
                .filter(id -> {
                    Game game = state.verified.get(id);
                    return game != null && !actionExecutor.narrativeObservations(game, state.research).isEmpty();
                })
                .toList();
    }

    private List<ToolSpec> actions(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds) {
        String preferences = preferenceSchema(preferenceEvidenceIds);
        return List.of(
                new ToolSpec(
                        UPDATE_PREFERENCES_TOOL,
                        "Persist explicit player-stated preferences without retrieval. Include the complete locale-matched playerReply when this update finishes the turn; omit it only when another useful action still needs the resulting observation.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"preferenceUpdates\":"
                                + preferences
                                + ",\"playerReply\":{\"type\":\"string\",\"description\":\"Complete locale-matched answer to publish unchanged when the preference update finishes this turn.\",\"minLength\":1}},\"required\":[\"preferenceUpdates\"]}"),
                new ToolSpec(
                        ASK_TOOL,
                        "Ask one natural high-value question only when a missing player choice changes the slate. preferenceUpdates keep stated numeric facts, never proposed options. Do not ask after read failure or when discovery/immediate cards can answer.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"minLength\":1},\"options\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferenceUpdates\":"
                                + clarificationPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"question\"]}"),
                new ToolSpec(
                        RESOLVE_TOOL,
                        "Resolve one formal/localized/original title copied from cited user evidence; never a sentence, nickname, person, list, or guessed alias. TARGET_GAME verifies one selectable card, then recommend_games writes the reply after seeing its facts. Other purposes set a comparison reference, discussion subject, or identity.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1},\"alternateTitles\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"purpose\":{\"type\":\"string\",\"enum\":[\"TARGET_GAME\",\"COMPARISON_REFERENCE\",\"DISCUSSION_SUBJECT\",\"IDENTITY_ONLY\"]},\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "}},\"required\":[\"title\",\"purpose\",\"evidence\"]}"),
                new ToolSpec(
                        BROWSE_TOOL,
                        catalogActionDescription(),
                        catalogActionSchema(preferenceEvidenceIds, currentTurnEvidenceIds)),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Search public sources once for an uncertain/current relationship, alias, event, organization, award, list, or source-backed title lead. subject is the exact cited identity phrase, not a guessed answer. goal selects the shape of this search result only; it never triggers a hidden BGG lookup or recommendation. After the observation, answer naturally or choose a separate catalog action.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "},\"subject\":{\"type\":\"string\",\"description\":\"Exact identity-bearing nickname, initials, award, event, organization, or relationship phrase; not the full question and not a guessed answer.\",\"minLength\":1},\"goal\":{\"type\":\"string\",\"description\":\"IDENTITY_ONLY for a public fact or relationship; SELECTABLE_CARDS when public title leads may be useful, while BGG verification remains a separate action.\",\"enum\":[\"IDENTITY_ONLY\",\"SELECTABLE_CARDS\"]},\"types\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}}},\"required\":[\"evidence\",\"subject\",\"goal\"]}"),
                new ToolSpec(
                        LOOKUP_TOOL,
                        "Load BGG facts only for observed conversation-context IDs that do not yet have verified details. The application pages the logical ID set across storage-sized batches under the shared run deadline.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647}}},\"required\":[\"bggIds\"]}"),
                new ToolSpec(
                        RESEARCH_TOOL,
                        "Research current reception or player-reported experience for already-verified games. Include every relevant verified bggId and ask one combined question; the application pages the logical set across configured model-candidate resource batches under the shared run deadline. After it returns, compare with attributed R observations or leave unsupported qualities unknown.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647}},\"question\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"bggIds\",\"question\"]}"),
                new ToolSpec(
                        RECOMMEND_TOOL,
                        "Finish a verified card recommendation after a card-producing read.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"playerReply\":{\"type\":\"string\",\"minLength\":1},\"playerReplyEvidenceIds\":{\"type\":\"array\",\"minItems\":0},\"selections\":{\"type\":\"array\",\"minItems\":1}},\"required\":[\"playerReply\",\"playerReplyEvidenceIds\",\"selections\"]}"),
                comparisonAction(List.of(), List.of(), List.of(), preferenceEvidenceIds),
                noMatchAction(List.of()));
    }

    private ToolSpec catalogAction(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds) {
        return new ToolSpec(
                BROWSE_TOOL,
                catalogActionDescription(),
                catalogActionSchema(preferenceEvidenceIds, currentTurnEvidenceIds));
    }

    private static boolean isDiscoveryAction(String action) {
        return DISCOVER_TOOL.equals(action);
    }

    private static String catalogActionDescription() {
        return "Search the local BGG catalog. SELECTABLE_CARDS returns a verified slate and its facts; recommend_games then writes and publishes the complete reply. IDENTITY_ONLY reads creator identity context. Filters AND. textQuery is soft; titleConstraint is the hard current-turn-cited title boundary. Omit requestedCount when unstated so the host applies its product default; an explicit requestedCount must cite the current-turn U id in evidence. Numeric/type constraints use preferenceUpdates.";
    }

    private String catalogActionSchema(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"purpose\":{\"type\":\"string\",\"enum\":[\"SELECTABLE_CARDS\",\"IDENTITY_ONLY\"]},\"types\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"categories\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"mechanics\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"designers\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}}"
                + ",\"publishers\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"families\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"minimumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"maximumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"minimumAverageRating\":{\"type\":\"number\",\"minimum\":0,\"maximum\":10},\"minimumRatingsCount\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100000000},\"textQuery\":{\"type\":\"string\",\"minLength\":1},\"titleConstraint\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"operator\":{\"type\":\"string\",\"enum\":[\"CONTAINS\"]},\"value\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"operator\",\"value\"]},\"evidence\":{\"type\":\"string\",\"description\":\"Current user-turn evidence for an explicit requestedCount and/or titleConstraint.\",\"enum\":"
                + jsonArray(currentTurnEvidenceIds)
                + "},\"sort\":{\"type\":\"string\",\"enum\":[\"RANK\",\"RATING\",\"POPULARITY\",\"NEWEST\",\"RELEVANCE\"]},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":"
                + properties.modelCandidateLimit()
                + "},\"requestedCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":"
                + properties.modelCandidateLimit()
                + "},\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":"
                + MAX_CATALOG_OFFSET
                + "},\"preferenceUpdates\":"
                + preferenceSchema(preferenceEvidenceIds)
                + "}}";
    }

    private ToolSpec recommendationAction(
            RecommendationAgentState state,
            List<Integer> candidateIds) {
        PublicationSeed pending = Objects.requireNonNull(
                state.pendingPublicationSeed,
                "pending recommendation publication is required");
        int selectionCount = Math.min(pending.requestedCount(), candidateIds.size());
        List<String> playerReplyEvidenceIds = candidateIds.stream()
                .map(state.verified::get)
                .filter(Objects::nonNull)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).keySet().stream())
                .distinct()
                .toList();
        String candidateSchemas = candidateIds.stream()
                .map(id -> recommendationSelectionSchema(state, id))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String countGuidance = candidateIds.size() < pending.requestedCount()
                ? " The player requested " + pending.requestedCount() + " cards, but only "
                        + candidateIds.size()
                        + " verified candidates remain. Prefer all useful candidates, but never invent or pad a weak card; explain any bounded shortfall naturally without claiming the catalog is exhausted."
                : " Select up to " + selectionCount + " useful candidates in the order you want them shown; never invent or pad a weak card.";
        return new ToolSpec(
                RECOMMEND_TOOL,
                "Finish the current verified recommendation. playerReply is the complete natural answer, not a short card lead: directly answer the request, explain the overall selection logic and material tradeoffs, and offer a useful next choice when appropriate. Bind candidate-specific factual wording with playerReplyEvidenceIds, or use an empty list only for genuinely generic wording. Each selection requires one evidence-bound why; add a tradeoff only when the same candidate's observations support a useful boundary. The application validates the complete candidate/evidence payload once and publishes every accepted text string unchanged. If validation fails, the action observation contains the full submitted payload, exact failing path, and current replacement contract; submit a complete replacement only when useful."
                        + countGuidance,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"playerReply\":{\"type\":\"string\",\"description\":\"Complete locale-matched answer that directly explains the selection and overall tradeoffs. Do not expose internal ids, evidence ids, tools, or workflow.\",\"minLength\":1},"
                        + "\"playerReplyEvidenceIds\":{\"type\":\"array\",\"description\":\"Internal observation ids supporting candidate-specific factual wording in playerReply; use [] for a generic lead and never show these ids to the player.\",\"minItems\":0,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(playerReplyEvidenceIds)
                        + "}},"
                        + "\"selections\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                        + selectionCount
                        + ",\"uniqueItems\":true,\"items\":{\"oneOf\":"
                        + candidateSchemas
                        + "}}},\"required\":[\"playerReply\",\"playerReplyEvidenceIds\",\"selections\"]}");
    }

    private String recommendationSelectionSchema(
            RecommendationAgentState state,
            int bggId) {
        Game game = Objects.requireNonNull(state.verified.get(bggId), "verified recommendation game is required");
        List<String> evidenceIds = actionExecutor.narrativeObservations(game, state.research)
                .keySet()
                .stream()
                .toList();
        String note = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"text\":{\"type\":\"string\",\"minLength\":1},"
                + "\"internalEvidenceIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                + jsonArray(evidenceIds)
                + "}}},\"required\":[\"text\",\"internalEvidenceIds\"]}";
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"bggId\":{\"type\":\"integer\",\"enum\":["
                + bggId
                + "]},\"why\":"
                + note
                + ",\"tradeoff\":"
                + note
                + "},\"required\":[\"bggId\",\"why\"]}";
    }

    private static ToolSpec comparisonAction(
            List<Integer> comparableIds,
            List<String> availableSubjects,
            List<String> availableEvidenceIds,
            List<String> preferenceEvidenceIds) {
        String idConstraint = comparableIds.isEmpty()
                ? "\"minimum\":1"
                : "\"enum\":" + comparableIds;
        String stringIdConstraint = comparableIds.isEmpty()
                ? "\"pattern\":\"^[1-9][0-9]{0,9}$\""
                : "\"enum\":" + jsonArray(comparableIds.stream().map(String::valueOf).toList());
        String evidenceConstraint = availableEvidenceIds.isEmpty()
                ? "\"minLength\":1"
                : "\"enum\":" + jsonArray(availableEvidenceIds);
        return new ToolSpec(
                COMPARE_TOOL,
                        "Finish a comparison of two to five verified conversation candidates on one to three observed axes. The JSON contains both the typed decision and the complete natural playerReply in the same call. Available observed attributes in this turn are "
                        + availableSubjects
                        + ". internalEvidenceIds must belong to the compared candidates and selected subjects; playerReply may make game-specific claims only from those observations. Publisher descriptions support their literal premise, setting, components, or advertised features; attributed reports support only what they report. Choose preferredBggId only when the evidence justifies a useful choice; otherwise use null and explain the remaining tradeoff naturally. Persist an explicit current-turn numeric/type correction in preferenceUpdates. Never use this action to replace candidates. Example shape: {\"candidateBggIds\":[11,22],\"subjects\":[\"duration\"],\"preferredBggId\":11,\"internalEvidenceIds\":[\"F11\",\"F22\"],\"playerReply\":\"如果今晚时间更紧，我会先选……\"}.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"candidateBggIds\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"integer\","
                        + idConstraint
                        + "}},\"subjects\":{\"type\":\"array\",\"description\":\"One to three observation attribute names from turnState. Unknown attributes remain visibly unknown instead of invalidating the comparison.\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferredBggId\":{\"description\":\"The one candidate you would choose from the selected observations, as an integer or canonical decimal ID string, or null when the evidence does not support choosing.\",\"anyOf\":[{\"type\":\"integer\","
                        + idConstraint
                        + "},{\"type\":\"string\","
                        + stringIdConstraint
                        + "},{\"type\":\"null\"}]},\"internalEvidenceIds\":{\"type\":\"array\",\"description\":\"The complete machine-only factual allowance for the final streamed comparison. Every ID must belong to a compared candidate and one of subjects.\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\","
                        + evidenceConstraint
                        + "}},\"playerReply\":{\"type\":\"string\",\"description\":\"The complete locale-matched comparison answer shown to the player. Use only the selected observations for game-specific factual clauses.\",\"minLength\":1},\"preferenceUpdates\":"
                        + preferenceSchema(preferenceEvidenceIds)
                        + "},\"required\":[\"candidateBggIds\",\"subjects\",\"preferredBggId\",\"internalEvidenceIds\",\"playerReply\"]}");
    }

    private static ToolSpec noMatchAction(List<String> relaxableSubjects) {
        String subjectConstraint = relaxableSubjects.isEmpty()
                ? "\"minLength\":1"
                : "\"enum\":" + jsonArray(relaxableSubjects);
        return new ToolSpec(
                NO_MATCH_TOOL,
                "Finish with zero cards and select exactly one currently offered explicit constraint whose removal would make at least one verified candidate eligible while every other explicit constraint stays unchanged. playerReply is the complete natural explanation shown now: name the real tradeoff for this turn without a stock no-match template and without claiming that relaxation guarantees success.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"relaxSubject\":{\"type\":\"string\","
                        + subjectConstraint
                        + "},\"playerReply\":{\"type\":\"string\",\"description\":\"The complete locale-matched no-match explanation and one actionable next choice.\",\"minLength\":1}},\"required\":[\"relaxSubject\",\"playerReply\"]}");
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String preferenceSchema(List<String> preferenceEvidenceIds) {
        return preferencePatchSchema(preferenceEvidenceIds, true);
    }

    private static String clarificationPreferenceSchema(List<String> preferenceEvidenceIds) {
        return preferencePatchSchema(preferenceEvidenceIds, false);
    }

    private static String preferencePatchSchema(
            List<String> preferenceEvidenceIds,
            boolean includeCategoricalFields) {
        String categoricalProperties = includeCategoricalFields
                ? ",\"type\":{\"description\":\"BGG product class only; never put COMPETITIVE, COOPERATIVE, or TEAM here.\",\"anyOf\":[{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]},{\"type\":\"null\"}]},"
                        + "\"interaction\":{\"description\":\"How players oppose or cooperate; never put a BGG product class here.\",\"anyOf\":[{\"type\":\"string\",\"enum\":[\"COMPETITIVE\",\"COOPERATIVE\",\"TEAM\"]},{\"type\":\"null\"}]}"
                : "";
        return "{\"type\":\"object\",\"additionalProperties\":false,\"minProperties\":2,\"properties\":{"
                + "\"evidence\":{\"type\":\"string\",\"enum\":"
                + evidenceEnum(preferenceEvidenceIds)
                + "},\"evidenceClassification\":{\"type\":\"string\",\"description\":\"DIRECT=cited number; INFERRED_GROUP_MEMBER_COUNT=counted members.\",\"enum\":[\"DIRECT\",\"INFERRED_GROUP_MEMBER_COUNT\"]},"
                + "\"playerCount\":{\"anyOf\":[{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},"
                + numericRangeSchema(1, 20)
                + ",{\"type\":\"null\"}]},"
                + "\"durationMinutes\":{\"anyOf\":["
                + numericRangeSchema(5, 1440)
                + ",{\"type\":\"null\"}]},"
                + "\"complexity\":{\"anyOf\":["
                + numericRangeSchema(0, 5)
                + ",{\"type\":\"null\"}]}"
                + categoricalProperties
                + "},\"required\":[\"evidence\"]}";
    }

    private static String numericRangeSchema(int minimum, int maximum) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"minProperties\":1,\"properties\":{"
                + "\"minimum\":{\"anyOf\":[{\"type\":\"number\",\"minimum\":"
                + minimum
                + ",\"maximum\":"
                + maximum
                + "},{\"type\":\"null\"}]},"
                + "\"maximum\":{\"anyOf\":[{\"type\":\"number\",\"minimum\":"
                + minimum
                + ",\"maximum\":"
                + maximum
                + "},{\"type\":\"null\"}]}}}";
    }

    private static String evidenceEnum(List<String> preferenceEvidenceIds) {
        List<String> allowedEvidenceIds = preferenceEvidenceIds.isEmpty()
                ? List.of("NO_USER_EVIDENCE")
                : preferenceEvidenceIds;
        return allowedEvidenceIds.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private Map<String, Object> turnState(RecommendationAgentState state) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("observationLegend", Map.of(
                "M", "verified BGG structured metadata or complete publisher description",
                "T", "BGG taxonomy label; use it only as a literal label, not as proof of another quality",
                "A", "attributed public report, limited to its literal claim",
                "R", "rulebook fact"));
        memory.put("profile", evidenceReview.profileForAgent(state.profile));
        if (state.titleConstraint != null) {
            memory.put("titleConstraint", Map.of(
                    "operator", "CONTAINS",
                    "value", state.titleConstraint.value(),
                    "evidenceId", state.titleConstraint.evidenceId()));
        }
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
        if (state.pendingPublicationSeed != null) {
            memory.put("pendingRecommendation", Map.of(
                    "candidateBggIds", pendingPublicationIds(state),
                    "requestedCount", state.pendingPublicationSeed.requestedCount()));
        }
        putIfNotEmpty(memory, "previouslyShownBggIds", state.previouslyShownIds.stream().toList());
        putIfNotEmpty(memory, "targetGameBggIds", state.targetGameIds.stream().toList());
        putIfNotEmpty(memory, "comparisonReferenceBggIds", state.comparisonReferenceIds.stream().toList());
        memory.put("referenceResolutionAttempts", state.referenceResolutionAttempts);
        if (state.namedGamePurpose != null) memory.put("namedGamePurpose", state.namedGamePurpose.name());
        putIfNotEmpty(memory, "publicCandidateLeads", state.discoveredCandidateLeads.stream()
                .map(lead -> Map.of(
                        "name", lead.name(),
                        "fitObservation", lead.fitObservation(),
                        "sourceIndexes", lead.sourceIndexes()))
                .toList());
        putIfNotEmpty(memory, "publicContextEvidence", state.publicContextEvidence.values().stream()
                .map(actionExecutor::publicContextObservation)
                .toList());
        putIfNotEmpty(memory, "publicContextSources", actionExecutor.sourceObservations(state.publicContextSources));
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
        memory.put("actionsTaken", List.copyOf(state.actions));
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
                "semanticPublicDiscovery", state.webResearchAvailable,
                "subjectiveFitResearch", state.webResearchAvailable && !state.verified.isEmpty());
    }

    String observation(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation could not be serialized", exception);
        }
    }

    private String contextualObservation(String observation, RecommendationAgentState state) {
        try {
            JsonNode parsed = json.readTree(observation);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("recommendation observation must be a JSON object");
            }
            object.set("availableCapabilities", json.valueToTree(availableCapabilities(state)));
            object.set("turnState", json.valueToTree(turnState(state)));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation context could not be serialized", exception);
        }
    }

    private void compactPriorToolState(List<Message> messages) {
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message.role() != BoardGameRecommendationModel.Role.TOOL) continue;
            try {
                JsonNode parsed = json.readTree(message.content());
                if (!(parsed instanceof ObjectNode object)) continue;
                object.remove(List.of(
                        "availableCapabilities",
                        "turnState"));
                messages.set(index, new Message(
                        BoardGameRecommendationModel.Role.TOOL,
                        json.writeValueAsString(object),
                        List.of(),
                        message.toolCallId(),
                        message.toolName()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("recommendation tool observation could not be compacted", exception);
            }
        }
    }

    private record SettledAction(
            RecommendationActions.ActionOutcome outcome,
            int stateEpoch,
            boolean reusedObservation) {

        private SettledAction(RecommendationActions.ActionOutcome outcome, int stateEpoch) {
            this(outcome, stateEpoch, false);
        }

        private SettledAction afterObservationReuse() {
            return new SettledAction(outcome, stateEpoch, true);
        }
    }

    String error(String code, String guidance) {
        return observation(Map.of("status", "ERROR", "code", code, "guidance", guidance));
    }

    String error(String code, String guidance, Map<String, ?> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", "ERROR");
        value.put("code", code);
        value.put("guidance", guidance);
        if (details != null) details.forEach(value::putIfAbsent);
        return observation(value);
    }

    ConversationRequest validate(ConversationRequest input) {
        if (input == null) throw new IllegalArgumentException("recommendation conversation request is required");
        String message = RecommendationConversationText.currentTurn(input.message());
        List<Integer> excluded = positiveIds(input.excludedBggIds(), "excludedBggIds");
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
        List<Integer> shown = positiveIds(input.shownBggIds(), "shownBggIds");
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

    private void validateIntegerRange(ConstraintRange<Integer> range, int minimum, int maximum, String label) {
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

    private List<Integer> positiveIds(List<Integer> values, String label) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(label + " must contain positive ids");
        }
        return values.stream().distinct().toList();
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
            throw new RunInterrupted();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("bounded recommendation operation failed", cause);
        }
    }

    private void emitProgress(
            Consumer<ProgressUpdate> listener,
            ProgressStage stage,
            ProgressPhase phase,
            ProgressAction action,
            ProgressFocus focus,
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
                    state.sourceCount,
                    focus));
        } catch (RuntimeException exception) {
            LOGGER.debug("Recommendation progress listener stopped accepting updates");
        }
    }

    private String observedAction(String action) {
        return OBSERVED_ACTIONS.contains(action) ? action : "unknown";
    }

    private OperationObservation startOperation(String stage, String action) {
        return new OperationObservation(stage, action);
    }

    private final class OperationObservation {
        private final Observation observation;
        private boolean stopped;

        private OperationObservation(String stage, String action) {
            observation = Observation.createNotStarted("rulepilot.recommendation.operation", observations)
                    .contextualName("recommendation-" + stage.replace('_', '-'))
                    .lowCardinalityKeyValue("stage", stage)
                    .lowCardinalityKeyValue("action", action)
                    .start();
        }

        private boolean stopped() {
            return stopped;
        }

        private void stop(String outcome, boolean recovered, Throwable failure) {
            if (stopped) return;
            stopped = true;
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.lowCardinalityKeyValue("recovered", Boolean.toString(recovered));
            if (failure != null) observation.error(failure);
            observation.stop();
        }
    }

    private final class ProgressTracker {
        private final Consumer<ProgressUpdate> listener;
        private final RecommendationAgentState state;
        private final long startedAt;
        private ProgressStage currentStage;
        private ProgressAction currentAction;
        private ProgressFocus currentFocus;
        private Observation currentObservation;
        private Observation.Scope currentObservationScope;

        private ProgressTracker(
                Consumer<ProgressUpdate> listener,
                RecommendationAgentState state,
                long startedAt) {
            this.listener = listener;
            this.state = state;
            this.startedAt = startedAt;
        }

        private void start(ProgressStage stage, ProgressAction action) {
            start(stage, action, null);
        }

        private void start(ProgressStage stage, ProgressAction action, ProgressFocus focus) {
            transition(stage, action, focus);
        }

        private void transition(ProgressStage stage, ProgressAction action, ProgressFocus focus) {
            if (currentStage == stage && currentAction == action && Objects.equals(currentFocus, focus)) return;
            complete();
            currentStage = stage;
            currentAction = action;
            currentFocus = focus;
            emitProgress(listener, stage, ProgressPhase.STARTED, action, focus, state, startedAt);
            currentObservation = Observation.createNotStarted("rulepilot.recommendation.stage", observations)
                    .contextualName("recommendation-" + stage.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .lowCardinalityKeyValue("stage", stage.name().toLowerCase(Locale.ROOT))
                    .lowCardinalityKeyValue("action", action.name().toLowerCase(Locale.ROOT))
                    .start();
            currentObservationScope = currentObservation.openScope();
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
            ProgressStage finishedStage = currentStage;
            ProgressAction finishedAction = currentAction;
            ProgressFocus finishedFocus = currentFocus;
            currentStage = null;
            currentAction = null;
            currentFocus = null;
            stopObservation(phase.name().toLowerCase(Locale.ROOT), null);
            emitProgress(listener, finishedStage, phase, finishedAction, finishedFocus, state, startedAt);
        }

        private void abort(Throwable failure) {
            if (currentStage == null) return;
            currentStage = null;
            currentAction = null;
            currentFocus = null;
            stopObservation("error", failure);
        }

        private void stopObservation(String outcome, Throwable failure) {
            Observation observation = currentObservation;
            Observation.Scope scope = currentObservationScope;
            currentObservation = null;
            currentObservationScope = null;
            if (observation == null) return;
            observation.lowCardinalityKeyValue("outcome", outcome);
            if (failure != null) observation.error(failure);
            try {
                if (scope != null) scope.close();
            } finally {
                observation.stop();
            }
        }
    }

    boolean chinese(String locale) {
        return "zh-CN".equals(locale);
    }

    private boolean simplifiedChineseLocale(String locale) {
        String value = locale == null ? "" : locale.strip().toLowerCase(Locale.ROOT);
        return value.equals("zh") || value.equals("zh-cn") || value.equals("zh-hans");
    }

    static class RunDeadlineExceeded extends RuntimeException {}

    static final class RunInterrupted extends RunDeadlineExceeded {}
}
