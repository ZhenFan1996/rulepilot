package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PROMPT_VERSION;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredOutput;
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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ResearchSource;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TurnCheckpoint;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationPublication.Permit;
import com.rulepilot.recommendation.application.RecommendationPublication.PublicationNarrative;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.DiscoveryPurpose;
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

/** Owns the bounded observe-decide-act loop, budgets, and truthful degradation. */
final class RecommendationReActLoop {

    static final int MAX_MODEL_CALLS = 7;
    private static final int MAX_DECISION_MODEL_CALLS = MAX_MODEL_CALLS - 1;
    private static final int MAX_ACTION_CALLS = 6;
    private static final int ACTION_SELECTION_OUTPUT_TOKENS = 512;
    private static final int EVIDENCE_RESPONSE_OUTPUT_TOKENS = 2_048;
    private static final String CANDIDATE_USE_SCHEMA = "{\"type\":\"string\",\"description\":\"Publish a useful verified slate now, or continue only when it is still context.\",\"enum\":[\"PUBLISH_CARDS\",\"CONTINUE_REACT\"]}";
    static final int MAX_REFERENCE_RESOLUTION_ATTEMPTS = 2;
    private static final Set<String> READ_ACTIONS = Set.of(
            RESOLVE_TOOL,
            BROWSE_TOOL,
            DISCOVER_TOOL,
            LOOKUP_TOOL,
            RESEARCH_TOOL);
    private static final Set<String> OBSERVED_ACTIONS = Set.of(
            REPLY_TOOL,
            IDENTITY_REPLY_TOOL,
            ASK_TOOL,
            RESOLVE_TOOL,
            BROWSE_TOOL,
            DISCOVER_TOOL,
            LOOKUP_TOOL,
            RESEARCH_TOOL,
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
        return converseValidated(
                request,
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
                maximumRecommendationResults());
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
        List<ToolSpec> actions = actions(
                preferenceEvidenceIds,
                currentTurnEvidenceIds,
                properties.resultCount());

        String input = agentInput(request, state, locale);
        List<Message> actionFoundation = List.of(
                Message.system(systemPromptV2()),
                Message.user(input));
        List<Message> messages = new ArrayList<>(actionFoundation);
        Map<String, SettledAction> settledActions = new LinkedHashMap<>();
        int stateEpoch = 0;

        while (state.modelCalls < MAX_DECISION_MODEL_CALLS && state.actionCalls < MAX_ACTION_CALLS) {
            state.modelCalls++;
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = availableActions(
                    state,
                    actions,
                    preferenceEvidenceIds,
                    currentTurnEvidenceIds);
            OperationObservation decisionObservation = startOperation(
                    "decision_model", "choose_next_action");
            try {
                List<Message> turnMessages = messages;
                Request modelRequest = new Request(
                        turnMessages,
                        currentActions,
                        outputTokenBudget(state),
                        ToolChoice.REQUIRED);
                turn = withinDeadline(
                        state,
                        () -> model.next(modelRequest, state.modelConfigurationOwner));
            } catch (RunInterrupted exception) {
                decisionObservation.stop("interrupted", false, exception);
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RunDeadlineExceeded exception) {
                decisionObservation.stop("deadline", false, exception);
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
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
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                decisionObservation.stop("output_limit", false, null);
                progress.fail();
                state.actions.add("MODEL_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "MODEL_OUTPUT_TRUNCATED");
            }
            if (turn.toolCalls().isEmpty()) {
                decisionObservation.stop("missing_action", false, null);
                LOGGER.warn("Recommendation Agent turn returned no required typed action");
                progress.fail();
                state.actions.add("UNSTRUCTURED_EVIDENCE_REPLY");
                return unavailable(state, locale, "UNSTRUCTURED_EVIDENCE_REPLY");
            }
            ToolCall call;
            if (turn.toolCalls().size() == 1) {
                call = turn.toolCalls().getFirst();
            } else {
                String actionName = turn.toolCalls().getFirst().name();
                boolean sameReadAction = READ_ACTIONS.contains(actionName)
                        && turn.toolCalls().stream().allMatch(candidate -> actionName.equals(candidate.name()));
                if (!sameReadAction) {
                    decisionObservation.stop("invalid_action_count", false, null);
                    LOGGER.warn(
                            "Recommendation ReAct turn returned {} incompatible actions (textCharacters={})",
                            turn.toolCalls().size(),
                            turn.text().length());
                    progress.fail();
                    state.actions.add("INVALID_ACTION_COUNT");
                    return unavailable(state, locale, "INVALID_ACTION_COUNT");
                }
                // Some compatible providers emit parallel alternatives even when parallel calls are
                // disabled. These capabilities are side-effect-free reads, so one bounded read is enough;
                // executing every variant would add cost without giving the model an observation between them.
                call = turn.toolCalls().getFirst();
                state.actions.add("COALESCED_PARALLEL_READ_ACTIONS:" + turn.toolCalls().size());
            }
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
                reused = true;
                outcome = settled.outcome();
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
                try {
                    outcome = actionExecutor.execute(
                            call,
                            state,
                            request,
                            locale,
                            (stage, focus) -> progress.start(stage, progressAction(call.name()), focus));
                    actionObservation.stop(
                            outcome.rejected() ? "rejected" : "completed",
                            false,
                            null);
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
                        state,
                        progress,
                        outcome.response(),
                        answerPartListener);
            }
            PublicationSeed publicationSeed = outcome.publicationSeed();
            if (publicationSeed != null) {
                progress.complete();
                return publishRecommendationWithinBoundary(
                        state,
                        request,
                        locale,
                        publicationSeed,
                        progress,
                        answerPartListener);
            }
            if (outcome.rejected()) {
                progress.retry();
            } else {
                progress.complete();
            }
            String observation = budgetedObservation(outcome.observation(), state);
            compactPriorToolState(messages);
            messages.add(Message.assistant("", call));
            messages.add(Message.tool(call, observation));
        }
        progress.fail();
        state.actions.add("REACT_BUDGET_EXHAUSTED");
        return unavailable(state, locale, "BUDGET_EXHAUSTED");
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
            RecommendationAgentState state,
            ProgressTracker progress,
            ConversationResponse decision,
            Consumer<String> answerPartListener) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
        if (decision.assistantMessage().isBlank()) {
            progress.fail();
            throw new IllegalStateException("validated recommendation decision omitted its player reply");
        }
        answerPartListener.accept(decision.assistantMessage());
        progress.complete();
        logRun(decision);
        return decision;
    }

    private ConversationResponse publishRecommendationWithinBoundary(
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            PublicationSeed seed,
            ProgressTracker progress,
            Consumer<String> answerPartListener) {
        try {
            return publishRecommendation(
                    state,
                    request,
                    locale,
                    seed,
                    progress,
                    answerPartListener);
        } catch (RuntimeException exception) {
            progress.fail();
            String code = publicationFailureCode(exception);
            state.actions.add("PUBLICATION_FAILED:" + code);
            LOGGER.warn("Recommendation publication failed ({})", code);
            return unavailable(state, locale, code);
        }
    }

    private ConversationResponse publishRecommendation(
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            PublicationSeed seed,
            ProgressTracker progress,
            Consumer<String> answerPartListener) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
        Permit permit = publication.permit(
                state,
                seed,
                evidenceReview.preferenceEvidence(request).keySet());
        PublicationNarrative narrative =
                synthesizeRecommendationNarrative(state, request, locale, permit);
        ConversationResponse response = publication.publish(state, seed, narrative, locale);
        try {
            answerPartListener.accept(response.assistantMessage());
        } catch (RuntimeException exception) {
            // The cards and evidence boundary are already complete. A disconnected optional stream listener must
            // not erase them or create a second generation path; the durable response remains authoritative.
            LOGGER.debug("Recommendation answer listener stopped accepting the completed lead");
        }
        progress.complete();
        logRun(response);
        return response;
    }

    private PublicationNarrative synthesizeRecommendationNarrative(
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Permit permit) {
        if (!model.structuredPublicationConfigured(state.modelConfigurationOwner)) {
            state.actions.add("RECOMMENDATION_NARRATIVE_SKIPPED:CAPABILITY_UNAVAILABLE");
            return null;
        }
        if (state.modelCalls >= MAX_MODEL_CALLS) {
            state.actions.add("RECOMMENDATION_NARRATIVE_SKIPPED:MODEL_BUDGET");
            return null;
        }

        state.modelCalls++;
        OperationObservation operation = startOperation(
                "publication_model", "write_grounded_recommendation");
        try {
            Request modelRequest = recommendationNarrativeRequest(
                    state,
                    request,
                    locale,
                    permit);
            BoardGameRecommendationModel.StructuredTurn turn = withinDeadline(
                    state,
                    () -> model.streamStructured(
                            modelRequest,
                            state.modelConfigurationOwner,
                            ignored -> {}));
            PublicationNarrative narrative = publication.validateNarrative(turn.json(), permit);
            operation.stop(
                    turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT
                            ? "completed_at_output_limit"
                            : "completed",
                    false,
                    null);
            return narrative;
        } catch (RunInterrupted interrupted) {
            operation.stop("interrupted", false, interrupted);
            throw interrupted;
        } catch (RunDeadlineExceeded timeout) {
            operation.stop("deadline", true, timeout);
            state.actions.add("RECOMMENDATION_NARRATIVE_SKIPPED:TIME_BUDGET");
            return null;
        } catch (RuntimeException failure) {
            operation.stop("invalid_or_unavailable", true, failure);
            String code = failure instanceof BoardGameRecommendationModel.ProtocolFailure protocol
                    ? "MODEL_PROTOCOL_" + protocol.code()
                    : failure instanceof RecommendationPublication.InvalidPublication invalid
                            ? invalid.code().name()
                            : "MODEL_CALL_FAILED";
            state.actions.add("RECOMMENDATION_NARRATIVE_SKIPPED:" + code);
            LOGGER.warn(
                    "Grounded recommendation narrative was skipped (type={}, code={})",
                    failure.getClass().getSimpleName(),
                    code);
            return null;
        }
    }

    private Request recommendationNarrativeRequest(
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Permit permit) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("locale", locale);
        input.put("recentConversation", conversationEvidence(request));
        input.put("currentProfile", evidenceReview.profileForAgent(state.profile));
        input.put("candidateOrder", permit.selectedGames().stream()
                .map(game -> game.ranking().bggId())
                .toList());
        input.put("candidates", permit.selectedGames().stream()
                .map(game -> actionExecutor.finalResponseGameObservation(
                        game,
                        state.research,
                        permit.allowedEvidenceByGame().get(game.ranking().bggId()).keySet()))
                .toList());
        putIfNotEmpty(input, "researchSources", actionExecutor.sourceObservations(state.research.sources()));
        try {
            return new Request(
                    List.of(
                            Message.system(recommendationNarrativePrompt()),
                            Message.user(json.writeValueAsString(input))),
                    List.of(),
                    EVIDENCE_RESPONSE_OUTPUT_TOKENS,
                    ToolChoice.NONE,
                    new StructuredOutput(
                            "grounded_recommendation_publication",
                            recommendationNarrativeSchema(permit),
                            true));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "recommendation narrative input could not be serialized",
                    failure);
        }
    }

    private static String recommendationNarrativePrompt() {
        return """
                Write the final recommendation lead and candidate notes in the player's language. This is synthesis, not another selection or review: keep candidateOrder unchanged and return exactly one card object for each candidate. The lead is a typed part with text and evidenceIds; cite only current-request U evidence or observations owned by the selected candidates.

                Connect the player's stated situation to the supplied candidate-scoped observations in fluent, specific prose. Select only evidenceIds that genuinely support each part. Add a tradeoff only when the supplied evidence supports a useful decision boundary; otherwise return null. BGG taxonomy is only a literal label, publisher description supports only its literal premise/features, and an attributed public report must remain visibly attributed.

                Exact hard facts and numbers already appear in the card UI. Do not calculate, embellish, or introduce numerical, identity, rule, ranking, award, availability, or current-event facts outside the selected observations. Never mention evidence IDs, schemas, tools, validation, workflow, or hidden reasoning. Avoid repeated stock openings and labels such as “one verified reason” or “choice boundary”; write like a knowledgeable person helping this particular table choose.
                """;
    }

    private static String recommendationNarrativeSchema(Permit permit) {
        List<Integer> candidateIds = permit.selectedGames().stream()
                .map(game -> game.ranking().bggId())
                .toList();
        List<String> evidenceIds = permit.selectedGames().stream()
                .flatMap(game -> permit.allowedEvidenceByGame()
                        .get(game.ranking().bggId())
                        .keySet()
                        .stream())
                .distinct()
                .toList();
        int maximumCandidateEvidenceIds = permit.allowedEvidenceByGame().values().stream()
                .mapToInt(Map::size)
                .max()
                .orElseThrow();
        String evidenceIdSchema = "{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                + maximumCandidateEvidenceIds
                + ",\"uniqueItems\":true,"
                + "\"items\":{\"type\":\"string\",\"enum\":"
                + jsonArray(evidenceIds)
                + "}}";
        String leadEvidenceIdSchema = "{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                + permit.allowedLeadEvidenceIds().size()
                + ",\"uniqueItems\":true,"
                + "\"items\":{\"type\":\"string\",\"enum\":"
                + jsonArray(permit.allowedLeadEvidenceIds().stream().toList())
                + "}}";
        String leadSchema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"text\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200},"
                + "\"evidenceIds\":"
                + leadEvidenceIdSchema
                + "},\"required\":[\"text\",\"evidenceIds\"]}";
        String partSchema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"text\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":320},"
                + "\"evidenceIds\":"
                + evidenceIdSchema
                + "},\"required\":[\"text\",\"evidenceIds\"]}";
        int count = candidateIds.size();
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"lead\":"
                + leadSchema
                + ","
                + "\"cards\":{\"type\":\"array\",\"minItems\":"
                + count
                + ",\"maxItems\":"
                + count
                + ",\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"bggId\":{\"type\":\"integer\",\"enum\":"
                + candidateIds
                + "},\"why\":"
                + partSchema
                + ",\"tradeoff\":{\"anyOf\":["
                + partSchema
                + ",{\"type\":\"null\"}]}},\"required\":[\"bggId\",\"why\",\"tradeoff\"]}}},"
                + "\"required\":[\"lead\",\"cards\"]}";
    }

    private String publicationFailureCode(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RecommendationPublication.InvalidPublication invalid) {
                return invalid.code().name();
            }
            current = current.getCause();
        }
        return "PUBLICATION_PROJECTION_FAILED";
    }

    private int outputTokenBudget(RecommendationAgentState state) {
        if (state.discoveryPurpose == DiscoveryPurpose.IDENTITY_ONLY) return 512;
        return state.catalogCalls == 0 && state.webResearchCalls == 0
                ? ACTION_SELECTION_OUTPUT_TOKENS
                : EVIDENCE_RESPONSE_OUTPUT_TOKENS;
    }

    private ProgressAction progressAction(String action) {
        return switch (action) {
            case REPLY_TOOL, IDENTITY_REPLY_TOOL -> ProgressAction.REPLY_TO_USER;
            case ASK_TOOL -> ProgressAction.ASK_USER;
            case RESOLVE_TOOL -> ProgressAction.RESOLVE_BGG_GAME;
            case BROWSE_TOOL -> ProgressAction.BROWSE_BGG_CATALOG;
            case DISCOVER_TOOL -> ProgressAction.DISCOVER_PUBLIC_CANDIDATES;
            case LOOKUP_TOOL -> ProgressAction.LOOKUP_BGG_GAMES;
            case RESEARCH_TOOL -> ProgressAction.RESEARCH_GAME_FIT;
            case COMPARE_TOOL -> ProgressAction.COMPARE_CANDIDATES;
            case NO_MATCH_TOOL -> ProgressAction.REPORT_NO_MATCH;
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
            data.put("defaultRecommendationCount", properties.resultCount());
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private static String systemPromptV2() {
        return """
                You are RulePilot, a knowledgeable, natural board-game companion. Treat recentConversation as the complete request, honor its latest correction, and answer in the player's language.

                Choose exactly one supplied typed action per model turn. Typed arguments and cited user evidence own routing and memory; prose never does. Retrieve game facts instead of guessing. Write complete useful prose inside the typed action only when it is terminal. Ask at most one question, and only when one missing player choice materially changes the answer.

                Every candidate read must set requestedCount plus requestedCountBasis. When no count is stated, use defaultRecommendationCount and PRODUCT_DEFAULT. For an explicitly stated count, requestedCountBasis is that current user turn's U id; never reuse an older turn's count. limit is only retrieval size. preferenceUpdates is one evidence-scoped patch for player-stated preferences: playerCount, durationMinutes, and complexity are numeric; type is a BGG product class; interaction is COMPETITIVE, COOPERATIVE, or TEAM. Never save clarification options or inferred mood. Range patches preserve omitted bounds. Cited numbers are DIRECT; INFERRED_GROUP_MEMBER_COUNT means counting stated members.

                Prefer browse_bgg_catalog for cards and BGG facts; use resolve_bgg_game for an exact player-written title. Use public discovery once for an uncertain/current relationship involving a person, event, organization, game, or other entity. Public context may answer without a BGG carrier, but every selectable card still needs BGG verification. Use textQuery for concepts rather than inventing taxonomy, and never repeat the same read.

                Candidate actions retrieve and select only. After hard checks, a separate bounded structured turn writes the natural lead and evidence-bound card notes. Recommendations need cards; comparisons use compare_candidates. Never expose hidden reasoning, schemas, internal ids, or workflow.
                """;
    }

    private List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds) {
        List<Integer> recommendableIds = recommendableIds(state);
        List<Integer> comparableIds = comparableIds(state);
        List<String> relaxableSubjects = relaxableSubjects(state);
        boolean comparisonNeedsCandidateRetrieval = state.discoveryPurpose != DiscoveryPurpose.IDENTITY_ONLY
                && state.namedGamePurpose == NamedGamePurpose.COMPARISON_REFERENCE
                && !state.catalogBrowseAttempted
                && !state.discoveryAttempted;
        boolean verifiedSlateAvailable = !recommendableIds.isEmpty();
        boolean identityTurnCanFinish = state.discoveryPurpose == DiscoveryPurpose.IDENTITY_ONLY
                && (state.discoveryAttempted || state.catalogBrowseAttempted);
        boolean verifiedSelectableSlateStillNeedsCards = state.discoveryPurpose == DiscoveryPurpose.SELECTABLE_CARDS
                && state.discoveryProducedVerifiedGames
                && verifiedSlateAvailable;
        boolean unresolvedIdentityCanStillBeClarified = state.unresolvedPlayerTitle;
        boolean clarificationWouldMaskFailure = !unresolvedIdentityCanStillBeClarified
                && (state.clarificationBlockedByExecutionFailure
                        || state.catalogBrowseAttempted && state.verified.isEmpty()
                        || state.discoveryAttempted && state.verified.isEmpty());
        return actions.stream()
                .filter(action -> !state.unresolvedPlayerTitle
                        || RESOLVE_TOOL.equals(action.name())
                        || isDiscoveryAction(action.name())
                        || ASK_TOOL.equals(action.name())
                        || REPLY_TOOL.equals(action.name()))
                .filter(action -> !verifiedSelectableSlateStillNeedsCards || !REPLY_TOOL.equals(action.name()))
                .filter(action -> !comparisonNeedsCandidateRetrieval
                        || !REPLY_TOOL.equals(action.name()) && !ASK_TOOL.equals(action.name()))
                .filter(action -> !clarificationWouldMaskFailure || !ASK_TOOL.equals(action.name()))
                .filter(action -> state.webResearchAvailable
                        || !isDiscoveryAction(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verified.isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> state.legalIds.stream().anyMatch(id -> !state.verified.containsKey(id))
                        || !LOOKUP_TOOL.equals(action.name()))
                .filter(action -> state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                        || !RESOLVE_TOOL.equals(action.name()))
                .filter(action -> !state.discoveryAttempted || !isDiscoveryAction(action.name()))
                .filter(action -> !state.researchAttempted || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> comparableIds.size() >= 2 || !COMPARE_TOOL.equals(action.name()))
                .filter(action -> !state.researchAttempted
                        || comparableIds.size() < 2
                        || !REPLY_TOOL.equals(action.name()))
                .filter(action -> !relaxableSubjects.isEmpty() || !NO_MATCH_TOOL.equals(action.name()))
                .filter(action -> !verifiedSlateAvailable
                        || REPLY_TOOL.equals(action.name())
                        || RESOLVE_TOOL.equals(action.name())
                        || COMPARE_TOOL.equals(action.name())
                        || RESEARCH_TOOL.equals(action.name())
                                && state.webResearchAvailable
                                && !state.researchAttempted
                        || isDiscoveryAction(action.name())
                                && state.webResearchAvailable
                                && !state.discoveryAttempted
                        || BROWSE_TOOL.equals(action.name())
                        || isDiscoveryAction(action.name()))
                .map(action -> BROWSE_TOOL.equals(action.name())
                                ? catalogAction(
                                        preferenceEvidenceIds,
                                        currentTurnEvidenceIds,
                                        properties.resultCount())
                        : COMPARE_TOOL.equals(action.name())
                                ? comparisonAction(
                                        comparableIds,
                                        availableComparisonSubjects(state, comparableIds),
                                        comparableEvidenceIds(state, comparableIds),
                                        preferenceEvidenceIds)
                        : NO_MATCH_TOOL.equals(action.name())
                                ? noMatchAction(relaxableSubjects)
                        : identityTurnCanFinish && REPLY_TOOL.equals(action.name())
                                ? identityReplyAction(state)
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
                        .contains(action)
                || action.startsWith("IGNORED_INVALID_PREFERENCE_UPDATE:"));
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
                .filter(game -> state.titleConstraint == null || state.titleConstraint.matches(game))
                .filter(game -> state.targetGameIds.contains(game.ranking().bggId())
                        || selector.eligible(game, state.profile))
                .map(game -> game.ranking().bggId())
                .toList();
    }

    private static List<ToolSpec> actions(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds,
            int defaultRecommendationCount) {
        String preferences = preferenceSchema(preferenceEvidenceIds);
        return List.of(
                new ToolSpec(
                        REPLY_TOOL,
                        "Finish without retrieval/new cards. playerReply is the complete answer, not status. referencedBggIds cite only verified discussion games and never create cards; resolve_bgg_game opens named games.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"playerReply\":{\"type\":\"string\",\"description\":\"Complete locale-matched player answer; no internal markers or unsupported game facts.\",\"minLength\":1,\"maxLength\":1200},\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"playerReply\"]}"),
                new ToolSpec(
                        ASK_TOOL,
                        "Ask one natural high-value question only when a missing player choice changes the slate. preferenceUpdates keep stated numeric facts, never proposed options. Do not ask after read failure or when discovery/immediate cards can answer.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"minLength\":1},\"options\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferenceUpdates\":"
                                + clarificationPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"question\"]}"),
                new ToolSpec(
                        RESOLVE_TOOL,
                        "Resolve one formal/localized/original title copied from cited user evidence; never a sentence, nickname, person, list, or guessed alias. TARGET_GAME requires playerReply and the same action immediately returns its selectable card. Other purposes set a comparison reference, discussion subject, or identity.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160},\"alternateTitles\":{\"type\":\"array\",\"maxItems\":2,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160}},\"purpose\":{\"type\":\"string\",\"enum\":[\"TARGET_GAME\",\"COMPARISON_REFERENCE\",\"DISCUSSION_SUBJECT\",\"IDENTITY_ONLY\"]},\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "},\"playerReply\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200}},\"required\":[\"title\",\"purpose\",\"evidence\"]}"),
                new ToolSpec(
                        BROWSE_TOOL,
                        catalogActionDescription(),
                        catalogActionSchema(
                                preferenceEvidenceIds,
                                currentTurnEvidenceIds,
                                defaultRecommendationCount)),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Verify one uncertain/current relationship involving a person, event, organization, alias, award, or list. subject is the exact cited identity phrase, not a guessed answer. afterIdentity says whether sourced context answers the turn or selectable cards remain. Supply requestedCount/basis. Publish useful selectable cards immediately; use CONTINUE_REACT only when the games remain identity context.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "},\"subject\":{\"type\":\"string\",\"description\":\"Exact identity-bearing nickname, initials, award, or relationship phrase; not the full question and not a guessed answer.\",\"minLength\":1,\"maxLength\":80},\"afterIdentity\":{\"type\":\"string\",\"enum\":[\"REPLY_WITH_IDENTITY\",\"RECOMMEND_WITH_CARDS\"]},\"candidateUse\":"
                                + CANDIDATE_USE_SCHEMA
                                + ",\"requestedCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"requestedCountBasis\":"
                                + requestedCountBasisSchema(currentTurnEvidenceIds)
                                + ",\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}}},\"required\":[\"evidence\",\"subject\",\"afterIdentity\",\"requestedCount\",\"requestedCountBasis\"]}"),
                new ToolSpec(
                        LOOKUP_TOOL,
                        "Load BGG facts only for observed conversation-context IDs that do not yet have verified details.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"integer\",\"minimum\":1}}},\"required\":[\"bggIds\"]}"),
                new ToolSpec(
                        RESEARCH_TOOL,
                        "Research current reception or player-reported experience for already-verified games. For a comparison, include every compared bggId in this one bounded call and ask one combined question; after it returns, compare with the attributed R observations or leave unsupported qualities unknown.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"question\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":300}},\"required\":[\"bggIds\",\"question\"]}"),
                comparisonAction(List.of(), List.of(), List.of(), preferenceEvidenceIds),
                noMatchAction(List.of()));
    }

    private static ToolSpec catalogAction(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds,
            int defaultRecommendationCount) {
        return new ToolSpec(
                BROWSE_TOOL,
                catalogActionDescription(),
                catalogActionSchema(
                        preferenceEvidenceIds,
                        currentTurnEvidenceIds,
                        defaultRecommendationCount));
    }

    private static boolean isDiscoveryAction(String action) {
        return DISCOVER_TOOL.equals(action);
    }

    private static String catalogActionDescription() {
        return "Search the local BGG catalog. SELECTABLE_CARDS publishes the first useful verified slate immediately; IDENTITY_ONLY reads creator identity context without publishing. Filters AND. textQuery is soft; titleConstraint is the hard current-turn-cited title boundary. requestedCount/requestedCountBasis use defaultRecommendationCount+PRODUCT_DEFAULT when unstated, else explicit count+current-turn U id. Numeric/type constraints use preferenceUpdates.";
    }

    private static String catalogActionSchema(
            List<String> preferenceEvidenceIds,
            List<String> currentTurnEvidenceIds,
            int defaultRecommendationCount) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"purpose\":{\"type\":\"string\",\"enum\":[\"SELECTABLE_CARDS\",\"IDENTITY_ONLY\"]},\"types\":{\"type\":\"array\",\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"categories\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"mechanics\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"designers\":{\"type\":\"array\",\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}}"
                + ",\"publishers\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"families\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"minimumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"maximumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"minimumAverageRating\":{\"type\":\"number\",\"minimum\":0,\"maximum\":10},\"minimumRatingsCount\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100000000},\"textQuery\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":240},\"titleConstraint\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"operator\":{\"type\":\"string\",\"enum\":[\"CONTAINS\"]},\"value\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160}},\"required\":[\"operator\",\"value\"]},\"evidence\":{\"type\":\"string\",\"enum\":"
                + jsonArray(currentTurnEvidenceIds)
                + "},\"sort\":{\"type\":\"string\",\"enum\":[\"RANK\",\"RATING\",\"POPULARITY\",\"NEWEST\",\"RELEVANCE\"]},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"requestedCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"requestedCountBasis\":"
                + requestedCountBasisSchema(currentTurnEvidenceIds)
                + ",\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":200},\"preferenceUpdates\":"
                + preferenceSchema(preferenceEvidenceIds)
                + "},\"required\":[\"requestedCount\",\"requestedCountBasis\"]}";
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
                ? "\"minLength\":3,\"maxLength\":80"
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
                        + "}},\"playerReply\":{\"type\":\"string\",\"description\":\"The complete locale-matched comparison answer shown to the player. Use only the selected observations for game-specific factual clauses.\",\"minLength\":1,\"maxLength\":1200},\"preferenceUpdates\":"
                        + preferenceSchema(preferenceEvidenceIds)
                        + "},\"required\":[\"candidateBggIds\",\"subjects\",\"preferredBggId\",\"internalEvidenceIds\",\"playerReply\"]}");
    }

    private static ToolSpec noMatchAction(List<String> relaxableSubjects) {
        String subjectConstraint = relaxableSubjects.isEmpty()
                ? "\"minLength\":1,\"maxLength\":40"
                : "\"enum\":" + jsonArray(relaxableSubjects);
        return new ToolSpec(
                NO_MATCH_TOOL,
                "Finish with zero cards and select exactly one currently offered explicit constraint whose removal would make at least one verified candidate eligible while every other explicit constraint stays unchanged. playerReply is the complete natural explanation shown now: name the real tradeoff for this turn without a stock no-match template and without claiming that relaxation guarantees success.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"relaxSubject\":{\"type\":\"string\","
                        + subjectConstraint
                        + "},\"playerReply\":{\"type\":\"string\",\"description\":\"The complete locale-matched no-match explanation and one actionable next choice.\",\"minLength\":1,\"maxLength\":1200}},\"required\":[\"relaxSubject\",\"playerReply\"]}");
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static ToolSpec slateReplyAction() {
        return new ToolSpec(
                REPLY_TOOL,
                "Finish only when this turn needs no new candidates and its goal is already answered. playerReply is the actual natural answer shown to the player in this same call. Never narrate unfinished work or mention retrieved leads.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"playerReply\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200},"
                        + "\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}}},"
                        + "\"required\":[\"playerReply\"]}");
    }

    private static ToolSpec identityReplyAction(RecommendationAgentState state) {
        String playerReply = "\"playerReply\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1200,"
                + "\"description\":\"The complete player-facing reply, written freely in the player's language. Lead with the useful answer, sound warm and knowledgeable, and include enough context to feel like a real conversation. Public factual clauses must stay within the selected public evidence statements. Do not mention tools, retrieval, validation, schemas, or workflow.\"}";
        String publicEvidence = publicEvidenceProperty(state);
        String requiredPublicEvidence = state.hasVerifiedPublicContext() ? ",\"publicEvidenceIds\"" : "";
        if (state.hasVerifiedIdentity()) {
            int identityCount = state.discoveredRelationshipNames.size();
            return new ToolSpec(
                    IDENTITY_REPLY_TOOL,
                    "Finish when this verified identity answers the complete current request. If the player also asked for games, continue the ReAct loop. The application validates the typed identity and publishes your complete playerReply unchanged; wording, tone, background, detail, and conversational follow-up are yours.",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"VERIFIED\"]},\"entityKind\":{\"type\":\"string\",\"enum\":[\""
                            + state.discoveredRelationshipKind.name()
                            + "\"]},\"entityNames\":{\"type\":\"array\",\"minItems\":"
                            + identityCount
                            + ",\"maxItems\":"
                            + identityCount
                            + ",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                            + jsonArray(state.discoveredRelationshipNames)
                            + "}},"
                            + playerReply
                            + publicEvidence
                            + "},\"required\":[\"status\",\"entityKind\",\"entityNames\",\"playerReply\""
                            + requiredPublicEvidence
                            + "]}");
        }
        if (state.hasVerifiedPublicContext()) {
            return new ToolSpec(
                    IDENTITY_REPLY_TOOL,
                    "Finish when the source-backed public context answers the complete request. Select only evidence ids supplied by this discovery read. The application validates their ownership, publishes their sources, and shows your natural playerReply unchanged. Do not add public facts outside the selected statements.",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                            + "\"status\":{\"type\":\"string\",\"enum\":[\"SOURCED_CONTEXT\"]},"
                            + playerReply
                            + publicEvidence
                            + "},\"required\":[\"status\",\"playerReply\",\"publicEvidenceIds\"]}");
        }
        List<Integer> contextIds = state.verifiedIdentityContextIds();
        String contextProperty = contextIds.isEmpty()
                ? ""
                : ",\"contextBggIds\":{\"type\":\"array\",\"minItems\":"
                        + contextIds.size()
                        + ",\"maxItems\":"
                        + contextIds.size()
                        + ",\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"enum\":"
                        + contextIds
                        + "}}";
        String requiredContext = contextIds.isEmpty() ? "" : ",\"contextBggIds\"";
        return new ToolSpec(
                IDENTITY_REPLY_TOOL,
                "Finish this identity check with the typed UNRESOLVED conclusion and any verified BGG context IDs. Do not write public facts or player-facing prose: the application owns the localized, evidence-safe failure explanation and distinguishes an unavailable lookup from insufficient evidence.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"UNRESOLVED\"]}"
                        + contextProperty
                        + "},\"required\":[\"status\""
                        + requiredContext
                        + "]}");
    }

    private static String publicEvidenceProperty(RecommendationAgentState state) {
        if (!state.hasVerifiedPublicContext()) return "";
        return ",\"publicEvidenceIds\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":4,\"uniqueItems\":true,"
                + "\"description\":\"Evidence ids from this discovery read that support every public factual clause in playerReply.\","
                + "\"items\":{\"type\":\"string\",\"enum\":"
                + jsonArray(state.publicContextEvidence.keySet().stream().toList())
                + "}}";
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

    private static String requestedCountBasisSchema(List<String> currentTurnEvidenceIds) {
        List<String> allowed = new ArrayList<>();
        allowed.add("PRODUCT_DEFAULT");
        allowed.addAll(currentTurnEvidenceIds);
        return "{\"type\":\"string\",\"description\":\"PRODUCT_DEFAULT only when the user stated no count; otherwise the current user turn U id that states it.\",\"enum\":"
                + jsonArray(allowed)
                + "}";
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
                "M", "verified BGG structured metadata or bounded publisher description",
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
        putIfNotEmpty(memory, "previouslyShownBggIds", state.previouslyShownIds.stream().toList());
        putIfNotEmpty(memory, "targetGameBggIds", state.targetGameIds.stream().toList());
        putIfNotEmpty(memory, "comparisonReferenceBggIds", state.comparisonReferenceIds.stream().toList());
        memory.put("referenceResolutionAttempts", state.referenceResolutionAttempts);
        if (state.namedGamePurpose != null) memory.put("namedGamePurpose", state.namedGamePurpose.name());
        if (state.hasVerifiedIdentity()) {
            memory.put("discoveredRelationship", Map.of(
                    "kind", state.discoveredRelationshipKind.name(),
                    "entityNames", state.discoveredRelationshipNames));
        }
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
            object.set("turnState", json.valueToTree(turnState(state)));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation budget could not be serialized", exception);
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
                        "remainingModelCalls",
                        "remainingActionCalls",
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

    private record SettledAction(RecommendationActions.ActionOutcome outcome, int stateEpoch) {}

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
