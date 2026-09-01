package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationException;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.Principal;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("!test")
public class BggRecommendationAgentStreamController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggRecommendationAgentStreamController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 35_000;

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;
    private final TaskExecutor executor;
    private final RecommendationConversationCoordinator conversations;
    private final ObjectProvider<PrivateAgentTraceService> traceServices;

    @Autowired
    public BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            @Qualifier("bggRecommendationStreamExecutor") TaskExecutor executor,
            RecommendationConversationCoordinator conversations,
            ObjectProvider<PrivateAgentTraceService> traceServices) {
        this.agent = agent;
        this.presentation = presentation;
        this.executor = executor;
        this.conversations = conversations;
        this.traceServices = traceServices;
    }

    public BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            TaskExecutor executor,
            RecommendationConversationCoordinator conversations) {
        this(agent, presentation, executor, conversations, null);
    }

    BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            TaskExecutor executor) {
        this(agent, presentation, executor, null);
    }

    @PostMapping(
            value = "/api/v1/bgg/recommendation-agent/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter converse(
            @RequestBody BggRecommendationAgentController.RecommendationConversationRequest request,
            @RequestParam(defaultValue = "en") String locale,
            Principal principal,
            HttpSession session) {
        ConversationRequest command = request.toCommand();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        sendProgress(emitter, open, new ProgressUpdate(
                BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST,
                BoardGameRecommendationAgent.ProgressPhase.STARTED,
                BoardGameRecommendationAgent.ProgressAction.UNDERSTAND_REQUEST,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0));
        if (!open.get()) return emitter;
        String modelConfigurationOwner = principal.getName();
        CaptureHandle capture = currentTrace(principal, session);
        UUID turnOperationId = UUID.randomUUID();
        BggRecommendationAgentController.captureUserTurn(
                capture,
                request,
                command.message(),
                locale,
                turnOperationId);
        try {
            executor.execute(() -> runConversation(
                    emitter,
                    open,
                    request,
                    command,
                    locale,
                    modelConfigurationOwner,
                    capture,
                    turnOperationId));
        } catch (RuntimeException exception) {
            String incidentId = UUID.randomUUID().toString();
            LOGGER.warn(
                    "Recommendation stream could not be scheduled: incidentId={}, type={}",
                    incidentId,
                    exception.getClass().getSimpleName());
            BggRecommendationAgentController.captureLifecycle(
                    capture,
                    turnOperationId,
                    LifecycleSignal.GAP,
                    "RECOMMENDATION_STREAM_QUEUE_REJECTED");
            sendError(emitter, open, "recommendation_unavailable", capture, turnOperationId);
        }
        return emitter;
    }

    SseEmitter converse(
            BggRecommendationAgentController.RecommendationConversationRequest request,
            String locale,
            Principal principal) {
        return converse(request, locale, principal, null);
    }

    private void runConversation(
            SseEmitter emitter,
            AtomicBoolean open,
            BggRecommendationAgentController.RecommendationConversationRequest request,
            ConversationRequest command,
            String locale,
            String modelConfigurationOwner,
            CaptureHandle capture,
            UUID turnOperationId) {
        AtomicBoolean answerPartSent = new AtomicBoolean();
        java.util.function.Consumer<String> answerPartListener = text -> {
            if (sendAnswerPart(emitter, open, text)) answerPartSent.set(true);
        };
        try {
            var presented = request.clientTurnId() != null && conversations != null
                    ? BggRecommendationAgentController.present(capture.enabled()
                            ? conversations.converse(
                                    request.toSessionTurn(command),
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    answerPartListener,
                                    capture,
                                    turnOperationId)
                            : conversations.converse(
                                    request.toSessionTurn(command),
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    answerPartListener), presentation)
                    : BggRecommendationAgentController.present(capture.enabled()
                            ? agent.converse(
                                    command,
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    answerPartListener,
                                    capture,
                                    turnOperationId)
                            : agent.converse(
                                    command,
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    answerPartListener), locale, presentation);
            if (!open.get()) return;
            if (!answerPartSent.get()) sendAnswerPart(emitter, open, presented.assistantMessage());
            if (!open.get()) return;
            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(presented));
            BggRecommendationAgentController.capturePublication(capture, presented, turnOperationId);
            emitter.complete();
        } catch (RuntimeException | IOException exception) {
            String code = exception instanceof RecommendationConversationException conversationFailure
                    ? conversationFailure.code().name().toLowerCase(Locale.ROOT)
                    : "recommendation_unavailable";
            String incidentId = UUID.randomUUID().toString();
            LOGGER.warn(
                    "Recommendation stream did not complete: incidentId={}, type={}, code={}",
                    incidentId,
                    exception.getClass().getSimpleName(),
                    code);
            String failureCode = exception instanceof RecommendationConversationException
                    ? "RECOMMENDATION_CONVERSATION_" + code.toUpperCase(Locale.ROOT)
                    : "RECOMMENDATION_STREAM_FAILED";
            BggRecommendationAgentController.captureLifecycle(
                    capture,
                    turnOperationId,
                    LifecycleSignal.FAILURE,
                    failureCode);
            sendError(emitter, open, code, capture, turnOperationId);
        }
    }

    private CaptureHandle currentTrace(Principal principal, HttpSession session) {
        if (traceServices == null || session == null) return CaptureHandle.noop();
        try {
            PrivateAgentTraceService service = traceServices.getIfAvailable();
            if (service == null) return CaptureHandle.noop();
            CaptureHandle capture = service.current(principal, session);
            return capture == null || !capture.enabled() ? CaptureHandle.noop() : capture;
        } catch (RuntimeException ignored) {
            return CaptureHandle.noop();
        }
    }

    private void sendAgentProgress(SseEmitter emitter, AtomicBoolean open, ProgressUpdate update) {
        if (update.stage() == BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST
                && update.phase() == BoardGameRecommendationAgent.ProgressPhase.STARTED) return;
        if (update.action() == BoardGameRecommendationAgent.ProgressAction.STREAM_NATURAL_REPLY) {
            sendProgress(emitter, open, new ProgressUpdate(
                    update.stage(),
                    update.phase(),
                    null,
                    update.elapsedMs(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0));
            return;
        }
        sendProgress(emitter, open, update);
    }

    private boolean sendAnswerPart(SseEmitter emitter, AtomicBoolean open, String text) {
        if (!open.get() || text == null || text.isEmpty()) return false;
        try {
            emitter.send(SseEmitter.event()
                    .name("answer_part")
                    .data(new AnswerPart("message", text)));
            return true;
        } catch (IOException | RuntimeException exception) {
            open.set(false);
            LOGGER.debug("Recommendation answer stream disconnected before completion");
            return false;
        }
    }

    private void sendProgress(SseEmitter emitter, AtomicBoolean open, ProgressUpdate update) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(update.elapsedMs()))
                    .name("progress")
                    .data(new ProgressResponse(
                            update.stage().name().toLowerCase(Locale.ROOT),
                            update.phase().name().toLowerCase(Locale.ROOT),
                            update.action() == null
                                    ? null
                                    : update.action().name().toLowerCase(Locale.ROOT),
                            update.elapsedMs(),
                            update.observedCandidates(),
                            update.verifiedCandidates(),
                            update.hardRejectedCandidates(),
                            update.sourceCount())));
        } catch (IOException | RuntimeException exception) {
            open.set(false);
            LOGGER.debug("Recommendation progress stream disconnected before completion");
        }
    }

    private void sendError(
            SseEmitter emitter,
            AtomicBoolean open,
            String code,
            CaptureHandle capture,
            UUID turnOperationId) {
        if (!open.getAndSet(false)) return;
        try {
            StreamError error = new StreamError(code);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(error));
            BggRecommendationAgentController.captureErrorPublication(
                    capture,
                    error,
                    code,
                    turnOperationId);
        } catch (IOException | RuntimeException ignored) {
            // The client may already have closed the stream.
        } finally {
            emitter.complete();
        }
    }

    record ProgressResponse(
            String stage,
            String phase,
            String action,
            long elapsedMs,
            int observedCandidates,
            int verifiedCandidates,
            int hardRejectedCandidates,
            int sourceCount) {}

    record AnswerPart(String field, String text) {}

    record StreamError(String code) {}
}
