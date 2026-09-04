package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PROMPT_VERSION;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
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

    private static final Set<String> OBSERVED_ACTIONS = Set.of(
            SEARCH_TOOL,
            DISCOVER_TOOL,
            RESEARCH_TOOL,
            RECOMMEND_TOOL,
            COMPARE_TOOL);

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BoardGameRecommendationModel model;
    private final BoardGameRecommendationTools tools;
    private final BoardGameRecommendationSelector selector;
    private final ObjectMapper json;
    private final ExecutorService boundedCalls;
    private final long maximumRunMillis;
    private final int maximumOutputTokens;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions actionExecutor;
    private final RecommendationPublication publication;
    private final RecommendationDecisionBrief decisionBrief;
    private final RecommendationToolCatalog toolCatalog;
    private final RecommendationReadBatchExecutor readBatchExecutor;
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
        this.json = json;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        boundedCalls = AsyncContextPropagation.executorService(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recommendation-bounded-call-", 0).factory()));
        maximumRunMillis = properties.timeout().toMillis();
        maximumOutputTokens = properties.maxOutputTokens();
        evidenceReview = new RecommendationEvidenceReview(json, this);
        actionExecutor = new RecommendationActions(tools, selector, properties, json, evidenceReview, this);
        publication = new RecommendationPublication(
                selector,
                evidenceReview,
                actionExecutor,
                this,
                properties,
                json);
        decisionBrief = new RecommendationDecisionBrief(json);
        toolCatalog = new RecommendationToolCatalog(selector, properties, json, evidenceReview, actionExecutor);
        readBatchExecutor = new RecommendationReadBatchExecutor(actionExecutor, this, boundedCalls, json);
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
            Consumer<BoardGameRecommendationAgent.RecommendationPart> recommendationPartListener) {
        return converseValidated(
                validate(input),
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                recommendationPartListener,
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
        return converseValidated(
                request,
                requestedLocale,
                modelConfigurationOwner,
                progressListener,
                answerPartListener,
                ignored -> {},
                checkpointListener);
    }

    ConversationResponse converseValidated(
            ConversationRequest request,
            String requestedLocale,
            String modelConfigurationOwner,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener,
            Consumer<BoardGameRecommendationAgent.RecommendationPart> recommendationPartListener,
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
                        recommendationPartListener,
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
            Consumer<BoardGameRecommendationAgent.RecommendationPart> recommendationPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        long startedAt = System.nanoTime();
        String locale = simplifiedChineseLocale(requestedLocale) ? "zh-CN" : "en";
        RecommendationAgentState state = new RecommendationAgentState(
                request,
                startedAt,
                modelConfigurationOwner,
                tools.webResearchConfigured());
        ProgressTracker progress = new ProgressTracker(progressListener, state, startedAt);
        try {
            return converseWithProgress(
                    request,
                    locale,
                    state,
                    progress,
                    answerPartListener,
                    recommendationPartListener,
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
            Consumer<BoardGameRecommendationAgent.RecommendationPart> recommendationPartListener,
            Consumer<TurnCheckpoint> checkpointListener) {
        progress.start(ProgressStage.UNDERSTANDING_REQUEST, ProgressAction.UNDERSTAND_REQUEST);
        progress.complete();
        if (!model.configured(state.modelConfigurationOwner)) {
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.OBSERVE_AND_DECIDE);
            progress.fail();
            return unavailable(state, locale, "MODEL_NOT_CONFIGURED");
        }
        List<String> preferenceEvidenceIds = evidenceReview.preferenceEvidence(request).keySet().stream().toList();
        List<String> currentTurnEvidenceIds = preferenceEvidenceIds.isEmpty()
                ? List.of()
                : List.of(preferenceEvidenceIds.getLast());
        List<ToolSpec> actions = toolCatalog.actions(preferenceEvidenceIds, currentTurnEvidenceIds);

        String input = toolCatalog.agentInput(request, state, locale);
        List<Message> messages = new ArrayList<>(List.of(
                Message.system(RecommendationToolCatalog.systemPrompt()),
                Message.user(input)));
        Map<String, SettledAction> settledActions = new LinkedHashMap<>();
        Set<String> rejectedIncompatibleActionSets = new LinkedHashSet<>();
        Set<String> rejectedFreeFormPublications = new LinkedHashSet<>();
        int stateEpoch = 0;
        while (true) {
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.OBSERVE_AND_DECIDE);
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = toolCatalog.availableActions(
                    state,
                    actions,
                    preferenceEvidenceIds,
                    currentTurnEvidenceIds);
            OperationObservation decisionObservation = startOperation(
                    "agent_turn", "observe_and_decide");
            long modelCallStartedAt = System.nanoTime();
            state.modelCalls++;
            try {
                Request modelRequest = new Request(
                        messages,
                        currentActions,
                        maximumOutputTokens,
                        state.pendingPublicationSeed == null
                                ? ToolChoice.AUTO
                                : ToolChoice.REQUIRED);
                RecommendationDecisionBrief.StreamingPublisher decisionStream =
                        state.modelCalls == 1 && answerPartListener != null
                                ? decisionBrief.streamingPublisher(
                                        locale,
                                        currentActions.stream().map(ToolSpec::name).collect(java.util.stream.Collectors.toSet()),
                                        answerPartListener)
                                : null;
                Consumer<String> publicationStream = state.pendingPublicationSeed == null
                        || currentActions.size() != 1
                        || !RECOMMEND_TOOL.equals(currentActions.getFirst().name())
                        ? null
                        : publication.previewPublisher(
                                state,
                                locale,
                                recommendationPartListener);
                Consumer<String> argumentStream = decisionStream == null
                        ? publicationStream
                        : publicationStream == null
                                ? decisionStream
                                : decisionStream.andThen(publicationStream);
                turn = withinDeadline(
                        state,
                        () -> argumentStream == null
                                ? model.next(modelRequest, state.modelConfigurationOwner)
                                : model.nextStreaming(
                                        modelRequest,
                                        state.modelConfigurationOwner,
                                        argumentStream));
                if (decisionStream != null && turn.toolCalls().size() == 1) {
                    decisionStream.finish(turn.toolCalls().getFirst());
                }
            } catch (RunDeadlineExceeded exception) {
                state.recordModelCallElapsed(modelCallStartedAt);
                decisionObservation.stop(
                        exception instanceof RunInterrupted ? "interrupted" : "deadline",
                        false,
                        exception);
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
                if (state.pendingPublicationSeed != null) {
                    if (recommendableIds(state).isEmpty()) {
                        if (state.activeSearch != null) {
                            decisionObservation.stop("completed", true, null);
                            progress.complete();
                            return publishNoMatch(state, locale, progress);
                        }
                        decisionObservation.stop("rejected", false, null);
                        progress.fail();
                        state.actions.add("PUBLICATION_FAILED:PUBLICATION_CANDIDATE_MISSING");
                        return unavailable(
                                state,
                                locale,
                                "PUBLICATION_INVALID:PUBLICATION_CANDIDATE_MISSING");
                    }
                    decisionObservation.stop("rejected", false, null);
                    state.actions.add("REJECTED_PUBLICATION:PUBLICATION_MISSING");
                    if (!rejectedFreeFormPublications.add(turn.text())) {
                        progress.fail();
                        state.actions.add("NO_PROGRESS:REPEATED_INVALID_PUBLICATION");
                        return unavailable(
                                state,
                                locale,
                                "NO_PROGRESS:REPEATED_INVALID_PUBLICATION");
                    }
                    progress.retry();
                    messages.add(Message.assistant(turn.text(), List.of()));
                    messages.add(Message.user(observation(Map.of(
                            "validationError",
                                    Map.of(
                                            "code", "PUBLICATION_MISSING",
                                            "message", "Verified candidates may be published only through a currently available typed action."),
                            "allowedActions",
                                    currentActions.stream()
                                            .map(action -> Map.of(
                                                    "name", action.name(),
                                                    "inputSchema", action.inputSchema()))
                                            .toList(),
                            "allowedCandidateBggIds", recommendableIds(state)))));
                    continue;
                }
                decisionObservation.stop("completed", false, null);
                progress.complete();
                state.actions.add("FINAL_ANSWER");
                if (answerPartListener != null) answerPartListener.accept(turn.text());
                return publishNaturalResponse(state, locale, turn.text(), progress);
            }
            List<ToolCall> calls = turn.toolCalls();
            if (calls.size() > 1) {
                RecommendationReadBatchExecutor.Compatibility compatibility =
                        readBatchExecutor.compatibility(calls, currentActions);
                int batchStateEpoch = stateEpoch;
                boolean allFresh = calls.stream().noneMatch(call -> {
                    SettledAction settled = settledActions.get(actionFingerprint(call));
                    return settled != null && settled.stateEpoch() == batchStateEpoch;
                });
                if (compatibility.compatible() && allFresh) {
                    decisionObservation.stop("completed", false, null);
                    progress.complete();
                    List<RecommendationActions.ActionOutcome> outcomes;
                    try {
                        outcomes = readBatchExecutor.execute(calls, state, request, locale, progress::parallel);
                    } catch (RunDeadlineExceeded exception) {
                        state.actions.add("RUN_DEADLINE_EXCEEDED");
                        return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
                    }
                    List<String> batchObservations = new ArrayList<>(outcomes.size());
                    for (int index = 0; index < outcomes.size(); index++) {
                        ToolCall call = calls.get(index);
                        RecommendationActions.ActionOutcome outcome = outcomes.get(index);
                        if (outcome.response() != null || outcome.publicationArgumentsJson() != null) {
                            throw new IllegalStateException("read-only recommendation batch returned a terminal action");
                        }
                        if (!outcome.rejected()) stateEpoch++;
                        if (outcome.deterministicContractRejection()
                                || (!outcome.rejected() && readBatchExecutor.readOnly(call.name()))) {
                            settledActions.put(actionFingerprint(call), new SettledAction(outcome, stateEpoch));
                        }
                        if (outcome.settledRead()) {
                            checkpointListener.accept(new TurnCheckpoint(state.profile, state.verifiedForAgent()));
                        }
                        batchObservations.add(outcome.observation());
                    }
                    toolCatalog.appendActionObservations(
                            messages,
                            calls,
                            batchObservations,
                            state);
                    continue;
                }
                if (compatibility.compatible()) {
                    decisionObservation.stop("completed", false, null);
                    progress.complete();
                    List<String> observations = new ArrayList<>();
                    for (ToolCall call : calls) {
                        ActionStep step = performAction(
                                call,
                                currentActions,
                                settledActions,
                                stateEpoch,
                                state,
                                request,
                                locale,
                                progress,
                                checkpointListener);
                        stateEpoch = step.stateEpoch();
                        if (step.terminalResponse() != null) return step.terminalResponse();
                        observations.add(step.outcome().observation());
                    }
                    toolCatalog.appendActionObservations(messages, calls, observations, state);
                    continue;
                }
                String fingerprint = calls.stream()
                        .map(this::actionFingerprint)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("\n---\n"));
                decisionObservation.stop("rejected", false, null);
                LOGGER.warn(
                        "Recommendation ReAct turn returned {} incompatible actions (textCharacters={})",
                        calls.size(),
                        turn.text().length());
                state.actionCalls++;
                if (!rejectedIncompatibleActionSets.add(fingerprint)) {
                    progress.fail();
                    state.actions.add("REPEATED_INCOMPATIBLE_ACTIONS");
                    return unavailable(state, locale, "REPEATED_INCOMPATIBLE_ACTIONS");
                }
                state.actions.add("REJECTED_INCOMPATIBLE_ACTIONS");
                progress.retry();
                String observation = error(
                        "INCOMPATIBLE_ACTIONS",
                        "A multi-action turn is valid only when every call is an independent, currently available read. Keep mutations and terminal publication in their own decision after observing all prerequisite results.",
                        Map.of(
                                "submittedActions",
                                calls.stream()
                                        .map(call -> Map.of("callId", call.id(), "action", call.name()))
                                        .toList(),
                                "incompatibilities",
                                compatibility.issues()));
                toolCatalog.appendActionObservations(
                        messages,
                        calls,
                        java.util.Collections.nCopies(calls.size(), observation),
                        state);
                continue;
            }
            ToolCall call = calls.getFirst();
            decisionObservation.stop("completed", false, null);
            progress.complete();
            ActionStep step = performAction(
                    call,
                    currentActions,
                    settledActions,
                    stateEpoch,
                    state,
                    request,
                    locale,
                    progress,
                    checkpointListener);
            stateEpoch = step.stateEpoch();
            if (step.terminalResponse() != null) return step.terminalResponse();
            toolCatalog.appendActionObservations(
                    messages,
                    calls,
                    List.of(step.outcome().observation()),
                    state);
        }
    }

    private ActionStep performAction(
            ToolCall call,
            List<ToolSpec> currentActions,
            Map<String, SettledAction> settledActions,
            int stateEpoch,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            ProgressTracker progress,
            Consumer<TurnCheckpoint> checkpointListener) {
        state.actionCalls++;
        ToolCall executionCall = decisionBrief.withoutBrief(call);
        String fingerprint = actionFingerprint(executionCall);
        RecommendationActions.ActionOutcome outcome;
        OperationObservation actionObservation = startOperation(
                "typed_action", observedAction(call.name()));
        SettledAction settled = settledActions.get(fingerprint);
        if (settled != null && settled.stateEpoch() == stateEpoch) {
            actionObservation.stop("no_progress", false, null);
            state.actions.add("NO_PROGRESS:REPEATED_ACTION_OBSERVATION");
            progress.fail();
            return new ActionStep(
                    null,
                    stateEpoch,
                    unavailable(state, locale, "NO_PROGRESS:REPEATED_ACTION_OBSERVATION"));
        } else if (currentActions.stream().noneMatch(action -> action.name().equals(call.name()))) {
            state.actions.add("REJECTED_UNAVAILABLE_ACTION");
            outcome = RecommendationActions.ActionOutcome.rejectedContract(
                    error(
                            "ACTION_NOT_AVAILABLE",
                            "That capability is not available in this turn. Choose one action from the supplied list."),
                    "ACTION_NOT_AVAILABLE");
            actionObservation.stop("rejected", false, null);
        } else {
            try {
                outcome = actionExecutor.execute(
                        executionCall,
                        state,
                        request,
                        locale,
                        (stage, focus) -> progress.start(stage, progressAction(call.name(), stage), focus));
                if (outcome.publicationArgumentsJson() != null) {
                    try {
                        PreparedPublication prepared = publication.prepare(
                                state,
                                outcome.publicationArgumentsJson());
                        actionObservation.stop("completed", prepared.localized(), null);
                        progress.complete();
                        return new ActionStep(
                                null,
                                stateEpoch,
                                publishRecommendationWithinBoundary(
                                        state,
                                        locale,
                                        prepared,
                                        progress));
                    } catch (RecommendationPublication.InvalidPublication failure) {
                        String failureCode = failure.code().name();
                        state.actions.add("PUBLICATION_FAILED:" + failureCode);
                        actionObservation.stop("rejected", false, null);
                        progress.fail();
                        return new ActionStep(
                                null,
                                stateEpoch,
                                unavailable(state, locale, "PUBLICATION_INVALID:" + failureCode));
                    }
                } else {
                    actionObservation.stop(
                            outcome.rejected() ? "rejected" : "completed",
                            false,
                            null);
                }
            } catch (RunDeadlineExceeded exception) {
                actionObservation.stop(
                        exception instanceof RunInterrupted ? "interrupted" : "deadline",
                        false,
                        exception);
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return new ActionStep(
                        null,
                        stateEpoch,
                        unavailable(state, locale, "RUN_DEADLINE_EXCEEDED"));
            } catch (RuntimeException exception) {
                actionObservation.stop("error", false, exception);
                throw exception;
            }
        }
        int nextStateEpoch = stateEpoch;
        if (!outcome.rejected()) {
            nextStateEpoch++;
        }
        if (outcome.deterministicContractRejection()
                || (!outcome.rejected() && readBatchExecutor.readOnly(call.name()))) {
            settledActions.put(fingerprint, new SettledAction(outcome, nextStateEpoch));
        }
        if (outcome.settledRead()) {
            checkpointListener.accept(new TurnCheckpoint(state.profile, state.verifiedForAgent()));
        }
        if (!outcome.rejected()
                && SEARCH_TOOL.equals(call.name())
                && recommendableIds(state).isEmpty()) {
            progress.complete();
            return new ActionStep(
                    null,
                    nextStateEpoch,
                    publishNoMatch(state, locale, progress));
        }
        if (outcome.response() != null) {
            progress.complete();
            return new ActionStep(
                    null,
                    nextStateEpoch,
                    publishValidatedResponse(progress, outcome.response()));
        }
        if (outcome.rejected()) {
            progress.retry();
        } else {
            progress.complete();
        }
        return new ActionStep(outcome, nextStateEpoch, null);
    }

    String actionFingerprint(ToolCall call) {
        return readBatchExecutor.fingerprint(decisionBrief.withoutBrief(call));
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
                state.comparison,
                null);
        progress.complete();
        logRun(response);
        return response;
    }

    private ConversationResponse publishNoMatch(
            RecommendationAgentState state,
            String locale,
            ProgressTracker progress) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.REPLY_TO_USER);
        state.actions.add("NO_MATCH");
        ConversationResponse response = new ConversationResponse(
                Outcome.NO_MATCH,
                DecisionMode.MODEL_ASSISTED,
                chinese(locale)
                        ? "当前目录里没有找到符合条件的游戏。你可以告诉我最愿意放宽哪一项，我再换一组。"
                        : "The current catalog has no game matching those conditions. Tell me which constraint you would most like to relax, and I can try a different set.",
                state.selectionProfile(),
                null,
                state.sourceCount,
                state.freshVerifiedIds.size(),
                evidenceReview.userModelView(state, locale),
                List.of(),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        true,
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
            case SEARCH_TOOL -> ProgressAction.SEARCH_BGG_CATALOG;
            case DISCOVER_TOOL -> ProgressAction.DISCOVER_PUBLIC_RELATIONSHIP;
            case RESEARCH_TOOL -> ProgressAction.RESEARCH_GAME_FIT;
            case RECOMMEND_TOOL -> ProgressAction.RECOMMEND_GAMES;
            case COMPARE_TOOL -> ProgressAction.COMPARE_CANDIDATES;
            default -> ProgressAction.OBSERVE_AND_DECIDE;
        };
    }

    private ProgressAction progressAction(String action, ProgressStage stage) {
        return stage == ProgressStage.RESEARCHING_GAME_FIT
                ? ProgressAction.RESEARCH_GAME_FIT
                : progressAction(action);
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
                        reason,
                        failureDetailCode(code)),
                List.of(),
                null);
        logRun(response);
        return response;
    }

    private FailureReason failureReason(RecommendationAgentState state, String code) {
        if ("RUN_DEADLINE_EXCEEDED".equals(code)) return FailureReason.TIME_LIMIT;
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
        if ("NO_PROGRESS:REPEATED_ACTION_OBSERVATION".equals(code)) {
            return FailureReason.REPEATED_INVALID_ACTION;
        }
        if (state.actions.stream().anyMatch(action -> action.startsWith("PUBLICATION_FAILED:"))) {
            return FailureReason.PUBLICATION_REJECTED;
        }
        return FailureReason.SERVICE_FAILURE;
    }

    private String failureDetailCode(String code) {
        if (code == null || code.isBlank()) return null;
        int separator = code.indexOf(':');
        String detail = separator >= 0 ? code.substring(separator + 1) : code;
        return detail.matches("[A-Z][A-Z0-9_]*") ? detail : null;
    }

    private String playerFacingFailureMessage(FailureReason reason, String locale, String code) {
        boolean zh = chinese(locale);
        if ("NO_PROGRESS:REPEATED_ACTION_OBSERVATION".equals(code)) {
            return zh
                    ? "模型在同一状态下重复了完全相同的动作与观察结果。本轮已停止，避免继续做没有进展的往返。"
                    : "The model repeated the identical action and observation in the same state. The turn stopped instead of continuing a no-progress loop.";
        }
        return switch (reason) {
            case TIME_LIMIT -> zh
                    ? "本轮总时限已用完，模型或检索尚未返回可发布的完整结果。已核对的会话信息仍然保留，可以直接重试。"
                    : "This turn reached its total time limit before the model or retrieval returned a complete publishable result. Verified conversation context is still saved, so you can retry.";
            case RESOURCE_BUDGET_EXHAUSTED -> zh
                    ? "这个旧版本推荐轮次被已移除的累计资源预算终止。已核对的会话信息仍然保留，可以直接重试。"
                    : "This legacy recommendation turn was stopped by the cumulative resource budget that has since been removed. Verified conversation context is still saved, so you can retry.";
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
                    ? "模型在收到多动作依赖冲突说明后，仍重复提交完全相同的不兼容动作组。为避免无效往返，本轮已停止。"
                    : "After receiving the multi-action dependency conflict, the model repeated the identical incompatible action set. The turn stopped instead of entering another invalid round trip.";
            case REPEATED_INVALID_ACTION -> zh
                    ? "模型看到明确的参数校验错误（" + failureDetailCode(code)
                            + "）后，仍重复完全相同的无效动作。本轮已停止，避免无意义循环；原请求和已核对事实均已保留，可以原样重试。"
                    : "After receiving the precise parameter error " + failureDetailCode(code)
                            + ", the model repeated the identical invalid action. The turn stopped to avoid a pointless loop; the original request and verified facts remain available for an unchanged retry.";
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

    List<Integer> recommendableIds(RecommendationAgentState state) {
        return toolCatalog.recommendableIds(state);
    }

    String observation(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation could not be serialized", exception);
        }
    }

    private record ActionStep(
            RecommendationActions.ActionOutcome outcome,
            int stateEpoch,
            ConversationResponse terminalResponse) {}

    private record SettledAction(
            RecommendationActions.ActionOutcome outcome,
            int stateEpoch) {}

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
        String name = normalized(game.name());
        String originalName = normalized(game.originalName());
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

    private String normalized(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
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
                    .filter(game -> selector.eligible(game, state.selectionProfile()))
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
                    state.verified.size(),
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

    OperationObservation startOperation(String stage, String action) {
        return new OperationObservation(stage, action);
    }

    final class OperationObservation {
        private final Observation observation;
        private boolean stopped;

        private OperationObservation(String stage, String action) {
            observation = Observation.createNotStarted("rulepilot.recommendation.operation", observations)
                    .contextualName("recommendation-" + stage.replace('_', '-'))
                    .lowCardinalityKeyValue("stage", stage)
                    .lowCardinalityKeyValue("action", action)
                    .start();
        }

        void stop(String outcome, boolean recovered, Throwable failure) {
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

        private void parallel(
                String action,
                ProgressStage stage,
                ProgressPhase phase,
                ProgressFocus focus) {
            if (listener == null) return;
            synchronized (state) {
                synchronized (listener) {
                    emitProgress(
                            listener,
                            stage,
                            phase,
                            progressAction(action),
                            focus,
                            state,
                            startedAt);
                }
            }
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
