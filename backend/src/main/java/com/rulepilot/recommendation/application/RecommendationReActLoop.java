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
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;
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
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the bounded observe-decide-act loop, budgets, and truthful degradation. */
final class RecommendationReActLoop {

    static final int MAX_MODEL_CALLS = 6;
    private static final int MAX_DECISION_MODEL_CALLS = MAX_MODEL_CALLS - 1;
    private static final int MAX_ACTION_CALLS = 6;
    private static final long PUBLICATION_COMPLETION_RESERVE_MILLIS = 3_000;
    private static final int ACTION_SELECTION_OUTPUT_TOKENS = 512;
    private static final int EVIDENCE_RESPONSE_OUTPUT_TOKENS = 1_536;
    private static final int PUBLICATION_OUTPUT_TOKENS = 2_048;
    private static final String CANDIDATE_USE_SCHEMA = "{\"type\":\"string\",\"description\":\"PUBLISH_CARDS opens the grounded natural publication immediately after a useful slate. RESEARCH_THEN_PUBLISH allows exactly one current player-experience research read first. CONTINUE_REACT keeps planning when this read is only an identity carrier or cannot yet answer the full request.\",\"enum\":[\"PUBLISH_CARDS\",\"RESEARCH_THEN_PUBLISH\",\"CONTINUE_REACT\"]}";
    private static final String PUBLICATION_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"decision":{"type":"object","additionalProperties":false,"properties":{"requestedCount":{"type":"integer","minimum":1,"maximum":8},"selections":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"bggId":{"type":"integer","minimum":1}},"required":["bggId"]}},"referenceBggIds":{"type":"array","maxItems":2,"uniqueItems":true,"items":{"type":"integer","minimum":1}}},"required":["requestedCount","selections","referenceBggIds"]},"replyBlocks":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"object","additionalProperties":false,"properties":{"surface":{"type":"string","enum":["MESSAGE","CARD"]},"role":{"type":"string","enum":["NARRATIVE","WHY_FIT","TRADEOFF"]},"bggId":{"type":["integer","null"],"minimum":1},"internalEvidenceIds":{"type":"array","items":{"type":"string","minLength":3,"maxLength":80}},"text":{"type":"string","minLength":1,"maxLength":700}},"required":["surface","role","bggId","internalEvidenceIds","text"]}}},"required":["decision","replyBlocks"]}
            """
            .strip();
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
        publication = new RecommendationPublication(selector, evidenceReview, actionExecutor, this);
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
        List<ToolSpec> actions = actions(preferenceEvidenceIds);

        String input = agentInput(request, state, locale);
        List<Message> actionFoundation = List.of(
                Message.system(systemPromptV2()),
                Message.user(input));
        List<Message> messages = new ArrayList<>(actionFoundation);
        Map<String, SettledAction> settledActions = new LinkedHashMap<>();
        int stateEpoch = 0;
        PublicationSeed pendingPublication = null;

        while (state.modelCalls < MAX_DECISION_MODEL_CALLS && state.actionCalls < MAX_ACTION_CALLS) {
            state.modelCalls++;
            progress.start(ProgressStage.SELECTING_TOOLS, ProgressAction.CHOOSE_NEXT_ACTION);
            BoardGameRecommendationModel.Turn turn;
            List<ToolSpec> currentActions = availableActions(state, actions, preferenceEvidenceIds);
            if (pendingPublication != null) {
                currentActions = currentActions.stream()
                        .filter(action -> RESEARCH_TOOL.equals(action.name()))
                        .toList();
            }
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
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RunDeadlineExceeded exception) {
                if (pendingPublication != null) {
                    return publishWithoutOptionalResearch(
                            request,
                            state,
                            locale,
                            pendingPublication,
                            progress,
                            answerPartListener,
                            "TIME_BUDGET");
                }
                progress.fail();
                state.actions.add("RUN_DEADLINE_EXCEEDED");
                return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
            } catch (RuntimeException exception) {
                String failureCode = exception instanceof BoardGameRecommendationModel.ProtocolFailure protocol
                        ? "MODEL_PROTOCOL_FAILED:" + protocol.code()
                        : "MODEL_CALL_FAILED";
                LOGGER.warn(
                        "Recommendation ReAct turn failed (type={}, code={})",
                        exception.getClass().getSimpleName(),
                        failureCode);
                if (pendingPublication != null) {
                    return publishWithoutOptionalResearch(
                            request,
                            state,
                            locale,
                            pendingPublication,
                            progress,
                            answerPartListener,
                            exception instanceof BoardGameRecommendationModel.ProtocolFailure
                                    ? "MODEL_PROTOCOL_FAILED"
                                    : "MODEL_CALL_FAILED");
                }
                progress.fail();
                state.actions.add(failureCode);
                return unavailable(state, locale, failureCode);
            }
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                if (pendingPublication != null) {
                    return publishWithoutOptionalResearch(
                            request,
                            state,
                            locale,
                            pendingPublication,
                            progress,
                            answerPartListener,
                            "MODEL_OUTPUT_LIMIT");
                }
                progress.fail();
                state.actions.add("MODEL_OUTPUT_TRUNCATED");
                return unavailable(state, locale, "MODEL_OUTPUT_TRUNCATED");
            }
            if (turn.toolCalls().isEmpty()) {
                LOGGER.warn("Recommendation Agent turn returned no required typed action");
                if (pendingPublication != null) {
                    return publishWithoutOptionalResearch(
                            request,
                            state,
                            locale,
                            pendingPublication,
                            progress,
                            answerPartListener,
                            "ACTION_MISSING");
                }
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
                    LOGGER.warn(
                            "Recommendation ReAct turn returned {} incompatible actions (textCharacters={})",
                            turn.toolCalls().size(),
                            turn.text().length());
                    if (pendingPublication != null) {
                        return publishWithoutOptionalResearch(
                                request,
                                state,
                                locale,
                                pendingPublication,
                                progress,
                                answerPartListener,
                                "INVALID_ACTION_COUNT");
                    }
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
            progress.complete();
            state.actionCalls++;
            String fingerprint = actionFingerprint(call);
            RecommendationActions.ActionOutcome outcome;
            boolean reused = false;
            SettledAction settled = settledActions.get(fingerprint);
            if (settled != null && settled.stateEpoch() == stateEpoch) {
                reused = true;
                outcome = settled.outcome();
                state.actions.add(outcome.rejected()
                        ? "REUSED_ACTION_ERROR"
                        : "REUSED_READ_OBSERVATION");
            } else if (currentActions.stream().noneMatch(action -> action.name().equals(call.name()))) {
                state.actions.add("REJECTED_UNAVAILABLE_ACTION");
                if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
                outcome = RecommendationActions.ActionOutcome.rejected(error(
                        "ACTION_NOT_AVAILABLE",
                        "That capability is not available in this turn. Choose one action from the supplied list."));
            } else {
                try {
                    outcome = actionExecutor.execute(
                            call,
                            state,
                            request,
                            locale,
                            (stage, focus) -> progress.start(stage, progressAction(call.name()), focus));
                } catch (RunInterrupted exception) {
                    progress.fail();
                    state.actions.add("RUN_DEADLINE_EXCEEDED");
                    return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
                } catch (RunDeadlineExceeded exception) {
                    if (pendingPublication != null) {
                        return publishWithoutOptionalResearch(
                                request,
                                state,
                                locale,
                                pendingPublication,
                                progress,
                                answerPartListener,
                                "TIME_BUDGET");
                    }
                    progress.fail();
                    state.actions.add("RUN_DEADLINE_EXCEEDED");
                    return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
                }
            }
            if (pendingPublication != null && outcome.rejected()) {
                return publishWithoutOptionalResearch(
                        request,
                        state,
                        locale,
                        pendingPublication,
                        progress,
                        answerPartListener,
                        "ACTION_REJECTED");
            }
            if (!reused && !outcome.rejected()) {
                stateEpoch++;
            }
            if (!reused && (outcome.rejected() || READ_ACTIONS.contains(call.name()))) {
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
            if (!outcome.rejected()
                    && RESEARCH_TOOL.equals(call.name())
                    && pendingPublication != null) {
                publicationSeed = new PublicationSeed(
                        pendingPublication.candidateBggIds(),
                        pendingPublication.referenceBggIds(),
                        CandidateUse.PUBLISH_CARDS);
                pendingPublication = null;
            }
            if (publicationSeed != null) {
                if (publicationSeed.candidateUse() == CandidateUse.RESEARCH_THEN_PUBLISH
                        && state.webResearchAvailable
                        && state.modelCalls < MAX_DECISION_MODEL_CALLS) {
                    pendingPublication = publicationSeed;
                } else {
                    if (publicationSeed.candidateUse() == CandidateUse.RESEARCH_THEN_PUBLISH
                            && state.webResearchAvailable) {
                        state.actions.add("RESEARCH_SKIPPED_FOR_PUBLICATION_BUDGET");
                        publicationSeed = new PublicationSeed(
                                publicationSeed.candidateBggIds(),
                                publicationSeed.referenceBggIds(),
                                CandidateUse.PUBLISH_CARDS);
                    }
                    progress.complete();
                    return publishRecommendationWithinBoundary(
                            request,
                            state,
                            locale,
                            publicationSeed,
                            progress,
                            answerPartListener);
                }
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
        if (pendingPublication != null) {
            return publishWithoutOptionalResearch(
                    request,
                    state,
                    locale,
                    pendingPublication,
                    progress,
                    answerPartListener,
                    "BUDGET");
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

    private ConversationResponse publishWithoutOptionalResearch(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            PublicationSeed pendingPublication,
            ProgressTracker progress,
            Consumer<String> answerPartListener,
            String reason) {
        progress.retry();
        state.actions.add("RESEARCH_SKIPPED_FOR_PUBLICATION_" + reason);
        PublicationSeed verifiedFallback = new PublicationSeed(
                pendingPublication.candidateBggIds(),
                pendingPublication.referenceBggIds(),
                CandidateUse.PUBLISH_CARDS);
        return publishRecommendationWithinBoundary(
                request,
                state,
                locale,
                verifiedFallback,
                progress,
                answerPartListener);
    }

    private ConversationResponse publishRecommendationWithinBoundary(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            PublicationSeed seed,
            ProgressTracker progress,
            Consumer<String> answerPartListener) {
        try {
            return publishRecommendation(
                    request,
                    state,
                    locale,
                    seed,
                    progress,
                    answerPartListener);
        } catch (RunInterrupted exception) {
            progress.fail();
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
        } catch (RunDeadlineExceeded exception) {
            progress.fail();
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return unavailable(state, locale, "RUN_DEADLINE_EXCEEDED");
        } catch (RuntimeException exception) {
            progress.fail();
            String code = publicationFailureCode(exception);
            state.actions.add("PUBLICATION_FAILED:" + code);
            LOGGER.warn("Recommendation publication failed ({})", code);
            return unavailable(state, locale, code);
        }
    }

    private ConversationResponse publishRecommendation(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            PublicationSeed seed,
            ProgressTracker progress,
            Consumer<String> answerPartListener) {
        progress.start(ProgressStage.COMPOSING_RESPONSE, ProgressAction.STREAM_NATURAL_REPLY);
        AtomicReference<RecommendationPublication.Permit> permit = new AtomicReference<>();
        AtomicReference<RecommendationPublication.Session> session = new AtomicReference<>();
        PublicationLifecycle publicationLifecycle = new PublicationLifecycle();
        PublicationDelivery publicationDelivery = new PublicationDelivery(answerPartListener);
        Runnable closePublication = publicationLifecycle::close;
        RecommendationPublicationStream stream = new RecommendationPublicationStream(
                json,
                decision -> publicationLifecycle.mutate(() -> {
                        RecommendationPublication.Permit approved = publication.permit(decision, state, seed);
                        if (!permit.compareAndSet(null, approved)) {
                            throw new IllegalStateException("recommendation publication repeated its decision");
                        }
                        session.set(publication.open(approved, state, locale));
                        return null;
                    }),
                block -> {
                    List<String> snapshots = publicationLifecycle.beginDelivery(() -> {
                        RecommendationPublication.Session active = session.get();
                        if (active == null) {
                            throw new IllegalStateException("recommendation reply block arrived before its decision");
                        }
                        active.acceptBlock(block);
                        return active.drainAnswerSnapshots();
                    });
                    try {
                        // Scheduling happens after the state monitor is released. The delivery worker is serial and
                        // may call a network listener without ever owning the publication lifecycle lock.
                        publicationDelivery.enqueue(snapshots);
                    } finally {
                        publicationLifecycle.endDelivery();
                    }
                },
                65_536,
                32);
        Request modelRequest = new Request(
                List.of(
                        Message.system(publicationPrompt()),
                        Message.user(publicationContext(request, state, locale, seed))),
                List.of(),
                PUBLICATION_OUTPUT_TOKENS,
                ToolChoice.NONE,
                new StructuredOutput("recommendation_publication", PUBLICATION_SCHEMA, true));
        try {
            Optional<BoardGameRecommendationModel.StructuredTurn> completed = withinPublicationDeadline(
                    state,
                    closePublication,
                    () -> model.streamStructured(
                            modelRequest,
                            state.modelConfigurationOwner,
                            stream::accept));
            if (completed.isEmpty()) {
                throw new RecoverablePublicationFailure("PUBLICATION_TIME_BUDGET");
            }
            BoardGameRecommendationModel.StructuredTurn turn = completed.orElseThrow();
            if (turn.completionStatus() == BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT) {
                throw new RecoverablePublicationFailure("PUBLICATION_OUTPUT_LIMIT");
            }
            stream.finish();
            closePublication.run();
            if (permit.get() == null || session.get() == null) {
                throw new RecoverablePublicationFailure("PUBLICATION_DECISION_MISSING");
            }
            RecommendationPublication.Session active = session.get();
            ConversationResponse response = active.finish();
            publicationDelivery.enqueue(active.drainAnswerSnapshots());
            publicationDelivery.await(state);
            active.commit();
            progress.complete();
            logRun(response);
            return response;
        } catch (RuntimeException exception) {
            closePublication.run();
            if (exception instanceof RunDeadlineExceeded) throw exception;
            if (hasCause(exception, RecommendationPublication.DeliveryFailure.class)) throw exception;
            String code = publicationFailureCode(exception);
            LOGGER.warn(
                    "Recommendation publication prose degraded after verified candidates (type={}, code={})",
                    exception.getClass().getSimpleName(),
                    code);
            ConversationResponse response = recoverVerifiedPublication(
                    state,
                    locale,
                    seed,
                    permit.get(),
                    session.get(),
                    publicationDelivery,
                    code);
            progress.complete();
            logRun(response);
            return response;
        } finally {
            publicationDelivery.close();
        }
    }

    private ConversationResponse recoverVerifiedPublication(
            RecommendationAgentState state,
            String locale,
            PublicationSeed seed,
            RecommendationPublication.Permit approved,
            RecommendationPublication.Session active,
            PublicationDelivery publicationDelivery,
            String code) {
        RecommendationPublication.Permit safePermit = approved;
        RecommendationPublication.Session safeSession = active;
        if (safePermit == null) {
            ObjectNode decision = json.createObjectNode();
            List<Integer> candidateIds = seed.candidateBggIds().stream()
                    .limit(state.maximumRecommendationResults)
                    .toList();
            decision.put("requestedCount", candidateIds.size());
            ArrayNode selections = decision.putArray("selections");
            candidateIds.forEach(id -> selections.addObject().put("bggId", id));
            decision.putArray("referenceBggIds");
            safePermit = publication.permit(decision, state, seed);
        }
        if (safeSession == null) {
            safeSession = publication.open(safePermit, state, locale);
        }
        ConversationResponse response = safeSession.finishWithVerifiedCandidates(code);
        publicationDelivery.enqueue(safeSession.drainAnswerSnapshots());
        publicationDelivery.await(state);
        safeSession.commit();
        return response;
    }

    private String publicationContext(
            ConversationRequest request,
            RecommendationAgentState state,
            String locale,
            PublicationSeed seed) {
        try {
            Set<Integer> allowedIds = java.util.stream.Stream.concat(
                            seed.candidateBggIds().stream(), seed.referenceBggIds().stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("locale", locale);
            data.put("currentProfile", evidenceReview.profileForAgent(state.profile));
            data.put("recentConversation", conversationEvidence(request));
            data.put("completeCurrentUserRequest", request.message());
            data.put("maximumResultCount", state.maximumRecommendationResults);
            data.put("publicationCandidateBggIds", seed.candidateBggIds());
            data.put("candidateGames", seed.candidateBggIds().stream()
                    .map(state.verified::get)
                    .filter(Objects::nonNull)
                    .map(actionExecutor::gameObservation)
                    .toList());
            data.put("referenceBggIds", seed.referenceBggIds());
            data.put("referenceGames", seed.referenceBggIds().stream()
                    .map(state.verified::get)
                    .filter(Objects::nonNull)
                    .map(actionExecutor::gameObservation)
                    .toList());
            data.put("researchEvidence", state.research.games().stream()
                    .filter(game -> allowedIds.contains(game.bggId()))
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
            data.put("researchSources", actionExecutor.sourceObservations(state.research.sources()));
            data.put("shownBggIds", state.previouslyShownIds.stream().toList());
            data.put("excludedBggIds", state.excludedIds.stream().toList());
            data.put("targetGameBggIds", state.targetGameIds.stream().toList());
            data.put("candidateUse", seed.candidateUse().name());
            if (state.hasVerifiedIdentity()) {
                data.put("discoveredRelationship", Map.of(
                        "kind", state.discoveredRelationshipKind.name(),
                        "entityNames", state.discoveredRelationshipNames));
            }
            if (!state.webResearchAvailable && !state.webResearchFailureCode.isBlank()) {
                data.put("webResearchFailureCode", state.webResearchFailureCode);
            }
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation publication context could not be serialized", exception);
        }
    }

    private static String publicationPrompt() {
        return """
                You are writing RulePilot's final grounded recommendation in the player's language. Return exactly one JSON object matching the supplied schema, with decision first and replyBlocks second. Do not call tools and do not output markdown around the JSON.

                First choose only publicationCandidateBggIds. requestedCount is the count the player actually requested, or a useful small default when they left it open. A selection contains only its bggId; evidence belongs on the exact prose block it supports. referenceBggIds may contain only the supplied comparison references and never a selected game.

                Then write a cohesive, knowledgeable answer in MESSAGE/NARRATIVE blocks. Split at natural paragraph boundaries so complete paragraphs can stream while you continue; do not fragment sentences or turn the answer into terse status copy. General conversational prose may use bggId null and no evidence. Every game-specific factual paragraph must cite the supporting internalEvidenceIds; the IDs are machine-only and must never appear in text.

                Add at least one CARD/WHY_FIT block for every selected game, grounded only in that game's cited selection evidence. Add a CARD/TRADEOFF block only when a concrete limitation could change the choice. CARD text is a separate annotation and is never concatenated into the main answer. Be candid when research was unavailable, and never invent reception, rules, identities, or player preferences.
                """;
    }

    private String publicationFailureCode(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RecoverablePublicationFailure failure) {
                return failure.code;
            }
            if (current instanceof RecommendationPublication.InvalidPublication invalid) {
                return invalid.code().name();
            }
            if (current instanceof RecommendationPublication.DeliveryFailure) {
                return "PUBLICATION_DELIVERY_FAILED";
            }
            if (current instanceof RecommendationPublicationStream.Failure failure) {
                if (failure.getCause() != null) {
                    current = failure.getCause();
                    continue;
                }
                return "PUBLICATION_" + failure.code().name();
            }
            if (current instanceof BoardGameRecommendationModel.ProtocolFailure protocol) {
                return "PUBLICATION_" + protocol.code();
            }
            current = current.getCause();
        }
        return "PUBLICATION_MODEL_FAILED";
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private int outputTokenBudget(RecommendationAgentState state) {
        if (state.discoveryPurpose == DiscoveryPurpose.IDENTITY_ONLY) return 512;
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
            case REPLY_TOOL, IDENTITY_REPLY_TOOL, ASK_TOOL, COMPARE_TOOL, NO_MATCH_TOOL ->
                ProgressStage.COMPOSING_RESPONSE;
            default -> ProgressStage.SELECTING_TOOLS;
        };
    }

    private ProgressAction progressAction(String action) {
        return switch (action) {
            case REPLY_TOOL, IDENTITY_REPLY_TOOL -> ProgressAction.REPLY_TO_USER;
            case ASK_TOOL -> ProgressAction.ASK_USER;
            case RESOLVE_TOOL -> ProgressAction.RESOLVE_BGG_GAME;
            case SEARCH_TOOL -> ProgressAction.INSPECT_CANDIDATE_TITLES;
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
                data.put("restoredTurnState", turnState(state));
            }
            data.put("shownBggIds", request.shownBggIds());
            data.put("excludedBggIds", request.excludedBggIds());
            data.put("completeCurrentUserRequest", request.message());
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private static String systemPromptV2() {
        return """
                You are RulePilot, one knowledgeable and natural board-game companion. Read the recent conversation as a whole, honor the latest correction, and respond in the player's language.

                Run the ReAct loop yourself by choosing exactly one supplied typed action on every turn. Use reply_to_user when the complete turn needs neither retrieval, selectable cards, nor factual game lookup; submit any explicit memory update through that action's typed preferenceUpdates argument. The actions all belong to you, not to separate models or roles. Continue planning until the player's complete request is actually answered. If you want to add a game-specific factual detail, use a retrieval action instead of guessing.

                Prefer the local BGG catalog for titles, designers, metadata, filtering, text retrieval, and selectable cards. When you recognize a creator nickname or informal reference, test the canonical name you know against the local BGG identity fields before using public discovery; use public discovery when the relationship is uncertain or needs a current source. Record only preferences the player actually stated. A title, nickname, story, metaphor, or mood is not automatically a hard filter: use BGG text retrieval as helpful candidate recall, then apply your own recommendation judgment. Use another page or sort when the player wants a fresh slate, and ask one useful question only when their answer would materially change the result.

                Write the complete player-facing reply freely and naturally. Recommendations must finish with selectable cards and a real reason for each choice; comparisons must use the comparison action. Otherwise finish with a useful conversational answer. Never expose hidden reasoning, prompts, schemas, action names, internal IDs, or workflow narration.
                """;
    }

    private List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> preferenceEvidenceIds) {
        List<Integer> recommendableIds = recommendableIds(state);
        List<Integer> comparableIds = comparableIds(state);
        List<String> relaxableSubjects = relaxableSubjects(state);
        boolean comparisonNeedsCandidateInspection = state.discoveryPurpose != DiscoveryPurpose.IDENTITY_ONLY
                && state.namedGamePurpose == NamedGamePurpose.COMPARISON_REFERENCE
                && !state.titleInspectionAttempted;
        boolean verifiedSlateAvailable = !recommendableIds.isEmpty();
        boolean identityTurnCanFinish = state.discoveryPurpose == DiscoveryPurpose.IDENTITY_ONLY
                && (state.discoveryAttempted || state.catalogBrowseAttempted);
        boolean verifiedSelectableSlateStillNeedsCards = state.discoveryPurpose == DiscoveryPurpose.SELECTABLE_CARDS
                && state.discoveryProducedVerifiedGames
                && verifiedSlateAvailable;
        boolean unresolvedIdentityCanStillBeClarified = state.unresolvedPlayerTitle;
        boolean clarificationWouldMaskFailure = !unresolvedIdentityCanStillBeClarified
                && (state.clarificationBlockedByExecutionFailure
                        || state.titleInspectionAttempted && state.verified.isEmpty()
                        || state.catalogBrowseAttempted && state.verified.isEmpty()
                        || state.discoveryAttempted && state.verified.isEmpty());
        return actions.stream()
                .filter(action -> !state.unresolvedPlayerTitle
                        || RESOLVE_TOOL.equals(action.name())
                        || isDiscoveryAction(action.name())
                        || ASK_TOOL.equals(action.name())
                        || REPLY_TOOL.equals(action.name()))
                .filter(action -> !verifiedSelectableSlateStillNeedsCards || !REPLY_TOOL.equals(action.name()))
                .filter(action -> !comparisonNeedsCandidateInspection
                        || !REPLY_TOOL.equals(action.name()) && !ASK_TOOL.equals(action.name()))
                .filter(action -> !clarificationWouldMaskFailure || !ASK_TOOL.equals(action.name()))
                .filter(action -> state.webResearchAvailable
                        || !isDiscoveryAction(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verified.isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> state.legalIds.stream().anyMatch(id -> !state.verified.containsKey(id))
                        || !LOOKUP_TOOL.equals(action.name()))
                .filter(action -> state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                        || !RESOLVE_TOOL.equals(action.name()))
                .filter(action -> !state.titleInspectionAttempted || !SEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.discoveryAttempted || !isDiscoveryAction(action.name()))
                .filter(action -> !state.discoveryAttempted
                        || state.discoveryProducedVerifiedGames
                        || !SEARCH_TOOL.equals(action.name()) && !BROWSE_TOOL.equals(action.name()))
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
                        || SEARCH_TOOL.equals(action.name())
                                && state.discoveryProducedVerifiedGames
                                && !state.titleInspectionAttempted
                        || BROWSE_TOOL.equals(action.name())
                        || state.titleInspectionAttempted
                                && (BROWSE_TOOL.equals(action.name()) || isDiscoveryAction(action.name())))
                .map(action -> BROWSE_TOOL.equals(action.name())
                                ? catalogAction(preferenceEvidenceIds)
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

    private static List<ToolSpec> actions(List<String> preferenceEvidenceIds) {
        String preferences = preferenceSchema(preferenceEvidenceIds);
        return List.of(
                new ToolSpec(
                        REPLY_TOOL,
                        "Finish a turn that needs no more retrieval and no new selectable card, including a memory-only confirmation. playerReply is the complete natural answer shown to the player; write the useful answer now, not a status line or promise of later work. Submit only explicit cited preferences through preferenceUpdates. referencedBggIds is optional and may contain only already-verified games being discussed; it grants factual context but never creates cards. For new/selectable candidates, choose a candidate-producing read and declare candidateUse so the application can open grounded natural publication. Use resolve_bgg_game for a named title whose guide or rulebook should open. Example: {\"playerReply\":\"可以，下一局我会优先看更短的选择。\",\"referencedBggIds\":[],\"preferenceUpdates\":[]}.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"playerReply\":{\"type\":\"string\",\"description\":\"The complete locale-matched natural answer shown to the player. It must not contain hidden IDs, evidence markers, workflow narration, or unsupported game facts.\",\"minLength\":1,\"maxLength\":1200},\"referencedBggIds\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"playerReply\"]}"),
                new ToolSpec(
                        ASK_TOOL,
                        "Ask exactly one natural question when the player invited guidance and one missing player-owned choice would materially change the slate. Choose the highest-value distinction instead of collecting every optional filter; options contain two or three direct answers when useful. Do not ask when the player explicitly requested immediate cards and a useful varied slate exists. Never ask the player to define an external identity while discovery is available, and never ask merely because a read failed. Preserve already stated numeric/type constraints.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"A natural locale-matched explanation of the missing decision followed by one useful clarification.\",\"minLength\":1},\"options\":{\"type\":\"array\",\"description\":\"Optional two or three direct answers.\",\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferenceUpdates\":"
                                + clarificationPreferenceSchema(preferenceEvidenceIds)
                                + "},\"required\":[\"question\"]}"),
                new ToolSpec(
                        RESOLVE_TOOL,
                        "Resolve one formal, localized, or original board-game title selected from a cited user turn. title, optional alternateTitles, purpose, and evidence are the complete typed interpretation; the application validates the evidence and BGG identity without parsing user prose. Copy only exact title spellings that are visibly present in the cited turn—never the whole sentence and never guessed aliases. Put the best title in title and up to two other explicit localized/original spellings in alternateTitles so the local catalog can try them in the same fast read. Do not submit a nickname, initials, person, award, publisher, or list as a formal title: answer directly when confident, or use public discovery when verification is needed. TARGET_GAME selects that game for its card, rulebook, guide, or questions and must include the complete natural playerReply; when any supplied title resolves, that same action immediately returns the selectable card without another model turn. COMPARISON_REFERENCE finds other games like it. DISCUSSION_SUBJECT supports factual conversation. IDENTITY_ONLY verifies only the title identity. A short correction keeps the earlier unresolved role.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"The best exact board-game title substring from the cited user turn, not the surrounding sentence.\",\"minLength\":1,\"maxLength\":160},\"alternateTitles\":{\"type\":\"array\",\"description\":\"At most two other exact localized or original title spellings explicitly present in the same cited user turn.\",\"maxItems\":2,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160}},\"purpose\":{\"type\":\"string\",\"enum\":[\"TARGET_GAME\",\"COMPARISON_REFERENCE\",\"DISCUSSION_SUBJECT\",\"IDENTITY_ONLY\"]},\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "},\"playerReply\":{\"type\":\"string\",\"description\":\"Required for TARGET_GAME: the complete locale-matched natural answer shown with the verified card.\",\"minLength\":1,\"maxLength\":1200}},\"required\":[\"title\",\"purpose\",\"evidence\"]}"),
                new ToolSpec(
                        SEARCH_TOOL,
                        "Inspect one to eight generated original/English candidate titles. Never include a player-named title. Results are BGG-verified. Include every explicit hard preference from the current user turn in preferenceUpdates so eligibility is established in this read. candidateUse declares whether this verified slate should publish now, receive one current-experience research read first, or remain only intermediate context.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"titles\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":120}},\"candidateUse\":"
                                + CANDIDATE_USE_SCHEMA
                                + ",\"preferenceUpdates\":"
                                + preferences
                                + "},\"required\":[\"titles\"]}"),
                new ToolSpec(
                        BROWSE_TOOL,
                        catalogActionDescription(),
                        catalogActionSchema(preferenceEvidenceIds)),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Verify one uncertain external board-game relationship such as a community nickname, initials, creator alias, award, or list. Use it only when you cannot test a confidently known canonical creator in local BGG or need sourced/current evidence. The long-lived verified cache is checked before a cold web search. subject is the exact identity-bearing phrase from the cited turn, preserving meaningful suffixes, numbers, and initials—not a guessed answer. afterIdentity declares whether identity itself answers the turn or cards are still required. For cards, candidateUse says whether representative verified games are already a useful slate, need one fit-research read, or remain context for a broader local browse; identity-only must CONTINUE_REACT.",
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(preferenceEvidenceIds)
                                + "},\"subject\":{\"type\":\"string\",\"description\":\"Exact identity-bearing nickname, initials, award, or relationship phrase; not the full question and not a guessed answer.\",\"minLength\":1,\"maxLength\":80},\"afterIdentity\":{\"type\":\"string\",\"enum\":[\"REPLY_WITH_IDENTITY\",\"RECOMMEND_WITH_CARDS\"]},\"candidateUse\":"
                                + CANDIDATE_USE_SCHEMA
                                + ",\"types\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}}},\"required\":[\"evidence\",\"subject\",\"afterIdentity\"]}"),
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

    private static ToolSpec catalogAction(List<String> preferenceEvidenceIds) {
        return new ToolSpec(
                BROWSE_TOOL,
                catalogActionDescription(),
                catalogActionSchema(preferenceEvidenceIds));
    }

    private static boolean isDiscoveryAction(String action) {
        return DISCOVER_TOOL.equals(action);
    }

    private static String catalogActionDescription() {
        return "Search the local BGG catalog without public web latency. The Agent may combine exact BGG types, categories, mechanics, designers, publishers, families, publication years, rating/popularity floors, stable sort, rank-page offset, and a short textQuery over cached BGG descriptions/tags. All supplied filters match together. Put explicit hard table constraints in preferenceUpdates before selection. For a metaphor or desired feeling, use textQuery as a retrieval rewrite instead of inventing an exact taxonomy filter, then judge the returned games yourself. Results are stable, not random: use offset or another sort when the player asks for a genuinely different batch, while shownBggIds prevents repeats. If you confidently know the canonical creator behind an informal reference, use that exact designer here before public discovery: IDENTITY_ONLY when identifying them fully answers the request, otherwise SELECTABLE_CARDS. candidateUse declares publish now, one fit-research read first, or continue planning; IDENTITY_ONLY must continue. Never repeat the same page.";
    }

    private static String catalogActionSchema(List<String> preferenceEvidenceIds) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"purpose\":{\"type\":\"string\",\"description\":\"Use SELECTABLE_CARDS for every search that should produce choices; use IDENTITY_ONLY only to verify one exact designer name for a conversation answer.\",\"enum\":[\"SELECTABLE_CARDS\",\"IDENTITY_ONLY\"]},\"candidateUse\":"
                + CANDIDATE_USE_SCHEMA
                + ",\"types\":{\"type\":\"array\",\"description\":\"Only literal player-supplied or previously verified BGG ranking types; omit for metaphors, moods, or subjective wishes.\",\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]}},\"categories\":{\"type\":\"array\",\"description\":\"Only exact literal or observed BGG category labels; never invent one from imaginative prose.\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"mechanics\":{\"type\":\"array\",\"description\":\"Only exact literal or observed BGG mechanic labels; never infer mechanics from mood.\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"designers\":{\"type\":\"array\",\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}}"
                + ",\"publishers\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"families\":{\"type\":\"array\",\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120}},\"minimumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"maximumPublicationYear\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2100},\"minimumAverageRating\":{\"type\":\"number\",\"minimum\":0,\"maximum\":10},\"minimumRatingsCount\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100000000},\"textQuery\":{\"type\":\"string\",\"description\":\"One short English retrieval query over cached BGG names, descriptions, categories, mechanics, and families. Use concrete concepts derived by the Agent; this is not an exact taxonomy assertion.\",\"minLength\":1,\"maxLength\":240},\"sort\":{\"type\":\"string\",\"description\":\"RANK is the stable default; RATING, POPULARITY, NEWEST, and RELEVANCE provide alternative factual orderings. RELEVANCE requires textQuery.\",\"enum\":[\"RANK\",\"RATING\",\"POPULARITY\",\"NEWEST\",\"RELEVANCE\"]},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8},\"offset\":{\"type\":\"integer\",\"description\":\"Stable result-page offset. Use a later offset when the current request explicitly wants another batch beyond shownBggIds.\",\"minimum\":0,\"maximum\":200},\"preferenceUpdates\":"
                + preferenceSchema(preferenceEvidenceIds)
                + "}}";
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
                        "Finish a comparison of two to five verified conversation candidates on one to three observed axes. The JSON contains both the typed decision and the complete natural playerReply in the same call. Available observed attributes in this turn are "
                        + availableSubjects
                        + ". internalEvidenceIds must belong to the compared candidates and selected subjects; playerReply may make game-specific claims only from those observations. Publisher descriptions support their literal premise, setting, components, or advertised features; attributed reports support only what they report. Choose preferredBggId only when the evidence justifies a useful choice; otherwise use null and explain the remaining tradeoff naturally. Persist an explicit current-turn numeric/type correction in preferenceUpdates. Never use this action to replace candidates. Example shape: {\"candidateBggIds\":[11,22],\"subjects\":[\"duration\"],\"preferredBggId\":11,\"internalEvidenceIds\":[\"F11\",\"F22\"],\"playerReply\":\"如果今晚时间更紧，我会先选……\"}.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"candidateBggIds\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":5,\"uniqueItems\":true,\"items\":{\"type\":\"integer\","
                        + idConstraint
                        + "}},\"subjects\":{\"type\":\"array\",\"description\":\"One to three observation attribute names from turnState. Unknown attributes remain visibly unknown instead of invalidating the comparison.\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1}},\"preferredBggId\":{\"description\":\"The one candidate you would choose from the selected observations, or null when the evidence does not support choosing.\",\"anyOf\":[{\"type\":\"integer\","
                        + idConstraint
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
                + "\"description\":\"The complete player-facing reply, written freely in the player's language. Lead with the useful answer, sound warm and knowledgeable, and include enough context to feel like a real conversation. Do not mention tools, retrieval, validation, schemas, or workflow.\"}";
        if (state.hasVerifiedIdentity()) {
            int identityCount = state.discoveredRelationshipNames.size();
            return new ToolSpec(
                    IDENTITY_REPLY_TOOL,
                    "Finish when this verified identity answers the complete current request. If the player also asked for games, a guide, or questions, continue the ReAct loop. The application validates the typed identity and publishes your complete playerReply unchanged; wording, tone, background, detail, and conversational follow-up are yours.",
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
                            + "},\"required\":[\"status\",\"entityKind\",\"entityNames\",\"playerReply\"]}");
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
                "Finish this identity check with the typed UNRESOLVED conclusion and any verified BGG context IDs. The application validates those structured values and publishes your complete natural playerReply unchanged.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"UNRESOLVED\"]},"
                        + playerReply
                        + contextProperty
                        + "},\"required\":[\"status\",\"playerReply\""
                        + requiredContext
                        + "]}");
    }

    private static String preferenceSchema(List<String> preferenceEvidenceIds) {
        return preferenceSchema(preferenceEvidenceIds, 1, "");
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
                + "\"evidence\":{\"type\":\"string\",\"description\":\"Evidence ID from the current user-message only. A player-written number such as 'we are 4 players' is a DIRECT current-session fact. Use a contextual count only when deriving an unstated number from a fully enumerated group; never cite game facts. Direct numeric values may become hard eligibility constraints. Enum values must be affirmatively named, not inferred or rejected; model-authored type and interaction categories remain reversible context and never become hard eligibility gates.\",\"enum\":"
                + evidenceEnum
                + "},\"evidenceClassification\":{\"type\":\"string\",\"description\":\"DIRECT=the player explicitly wrote the value, including a current-session group count. CONTEXTUAL_COMPLETE_GROUP=the Agent derived an unstated count because the speaker plus every named companion form the whole group; store only that derived count as a reversible working assumption.\",\"enum\":[\"DIRECT\",\"CONTEXTUAL_COMPLETE_GROUP\"]}},"
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

    private Map<String, Object> turnState(RecommendationAgentState state) {
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
        if (state.hasVerifiedIdentity()) {
            memory.put("discoveredRelationship", Map.of(
                    "kind", state.discoveredRelationshipKind.name(),
                    "entityNames", state.discoveredRelationshipNames));
        }
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

    private <T> Optional<T> withinPublicationDeadline(
            RecommendationAgentState state, Runnable closePublication, Supplier<T> operation) {
        // The run deadline bounds optional provider work. Once a typed, verified publication seed exists, the
        // deterministic projection may still finish immediately afterward instead of erasing safe cards.
        long publicationDeadlineMillis = publicationModelDeadlineMillis();
        long remainingMillis = publicationDeadlineMillis - state.elapsedMs();
        if (remainingMillis <= 0) {
            closePublication.run();
            return Optional.empty();
        }
        // Count the provider attempt on the owning thread. A timed-out Future has no successful get() happens-before
        // edge, so mutating the trace from its worker could make the recovered response undercount nondeterministically.
        state.modelCalls++;
        Future<T> pending = boundedCalls.submit(operation::get);
        try {
            return Optional.ofNullable(pending.get(remainingMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            closePublication.run();
            pending.cancel(true);
            return Optional.empty();
        } catch (InterruptedException exception) {
            closePublication.run();
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunInterrupted();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("bounded recommendation publication failed", cause);
        }
    }

    /** Serializes complete answer snapshots without letting player I/O own the model-state monitor. */
    private final class PublicationDelivery implements AutoCloseable {
        private final Consumer<String> listener;
        private final ExecutorService deliveryCalls = AsyncContextPropagation.executorService(
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("recommendation-publication-delivery-", 0).factory()));
        private final List<Future<?>> pending = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private boolean sealed;

        private PublicationDelivery(Consumer<String> listener) {
            this.listener = Objects.requireNonNull(listener, "answer listener is required");
        }

        synchronized void enqueue(List<String> snapshots) {
            if (sealed) {
                throw new RecommendationPublication.DeliveryFailure(
                        new IllegalStateException("recommendation publication delivery is closed"));
            }
            for (String snapshot : snapshots) {
                pending.add(deliveryCalls.submit(() -> {
                    if (failure.get() != null) return;
                    try {
                        listener.accept(snapshot);
                    } catch (RuntimeException | Error exception) {
                        failure.compareAndSet(null, exception);
                        throw exception;
                    }
                }));
            }
        }

        void await(RecommendationAgentState state) {
            List<Future<?>> scheduled;
            synchronized (this) {
                sealed = true;
                scheduled = List.copyOf(pending);
                deliveryCalls.shutdown();
            }
            try {
                for (Future<?> delivery : scheduled) {
                    long remainingMillis = Math.max(0, maximumRunMillis - state.elapsedMs());
                    // Future#get(0, ...) still returns an already completed delivery. This preserves a successful
                    // player write that settled at the boundary while refusing to wait beyond the run budget.
                    delivery.get(remainingMillis, TimeUnit.MILLISECONDS);
                }
            } catch (TimeoutException exception) {
                throw deliveryDeadlineExceeded();
            } catch (InterruptedException exception) {
                deliveryCalls.shutdownNow();
                Thread.currentThread().interrupt();
                throw new RunInterrupted();
            } catch (ExecutionException exception) {
                deliveryCalls.shutdownNow();
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw new RecommendationPublication.DeliveryFailure(runtime);
                }
                if (cause instanceof Error error) throw error;
                throw new RecommendationPublication.DeliveryFailure(
                        new IllegalStateException("recommendation publication delivery failed", cause));
            }
        }

        private RecommendationPublication.DeliveryFailure deliveryDeadlineExceeded() {
            deliveryCalls.shutdownNow();
            return new RecommendationPublication.DeliveryFailure(
                    new IllegalStateException("recommendation publication delivery exceeded the run deadline"));
        }

        @Override
        public synchronized void close() {
            sealed = true;
            deliveryCalls.shutdownNow();
        }
    }

    /** Fences parser state while allowing the resulting delivery submission to happen after releasing the lock. */
    private static final class PublicationLifecycle {
        private boolean accepting = true;
        private int deliveriesBeingScheduled;

        synchronized <T> T mutate(Supplier<T> mutation) {
            requireAccepting();
            return mutation.get();
        }

        synchronized <T> T beginDelivery(Supplier<T> mutation) {
            requireAccepting();
            deliveriesBeingScheduled++;
            try {
                return mutation.get();
            } catch (RuntimeException | Error failure) {
                deliveriesBeingScheduled--;
                notifyAll();
                throw failure;
            }
        }

        synchronized void endDelivery() {
            if (deliveriesBeingScheduled <= 0) {
                throw new IllegalStateException("recommendation publication delivery lifecycle is invalid");
            }
            deliveriesBeingScheduled--;
            notifyAll();
        }

        void close() {
            boolean interrupted = false;
            synchronized (this) {
                accepting = false;
                while (deliveriesBeingScheduled > 0) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                throw new RunInterrupted();
            }
        }

        private void requireAccepting() {
            if (!accepting) {
                throw new IllegalStateException("recommendation publication is no longer accepting output");
            }
        }
    }

    long publicationModelDeadlineMillis() {
        if (maximumRunMillis <= 1) return maximumRunMillis;
        long completionReserveMillis = Math.min(
                PUBLICATION_COMPLETION_RESERVE_MILLIS,
                Math.max(1, maximumRunMillis / 2));
        return maximumRunMillis - completionReserveMillis;
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

    private static final class RecoverablePublicationFailure extends RuntimeException {
        private final String code;

        private RecoverablePublicationFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    static class RunDeadlineExceeded extends RuntimeException {}

    static final class RunInterrupted extends RunDeadlineExceeded {}
}
