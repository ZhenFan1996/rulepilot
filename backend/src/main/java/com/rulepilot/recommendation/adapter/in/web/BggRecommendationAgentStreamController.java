package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationProperties;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationException;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private static final Duration STREAM_COMPLETION_GRACE = Duration.ofSeconds(5);

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;
    private final TaskExecutor executor;
    private final RecommendationConversationCoordinator conversations;
    private final long streamTimeoutMillis;

    @Autowired
    public BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            @Qualifier("bggRecommendationStreamExecutor") TaskExecutor executor,
            RecommendationConversationCoordinator conversations,
            BoardGameRecommendationProperties properties) {
        this.agent = agent;
        this.presentation = presentation;
        this.executor = executor;
        this.conversations = conversations;
        this.streamTimeoutMillis = streamTimeoutMillis(properties.timeout());
    }

    static long streamTimeoutMillis(Duration recommendationTimeout) {
        return recommendationTimeout.plus(STREAM_COMPLETION_GRACE).toMillis();
    }

    @PostMapping(
            value = "/api/v1/bgg/recommendation-agent/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter converse(
            @RequestBody BggRecommendationAgentController.RecommendationConversationRequest request,
            @RequestParam(defaultValue = "en") String locale,
            Principal principal) {
        ConversationRequest command = request.toCommand();
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
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
        try {
            String modelConfigurationOwner = principal.getName();
            executor.execute(() -> runConversation(
                    emitter, open, request, command, locale, modelConfigurationOwner));
        } catch (RuntimeException exception) {
            String incidentId = UUID.randomUUID().toString();
            LOGGER.warn(
                    "Recommendation stream could not be scheduled: incidentId={}, type={}",
                    incidentId,
                    exception.getClass().getSimpleName(),
                    exception);
            sendError(emitter, open, "recommendation_unavailable");
        }
        return emitter;
    }

    private void runConversation(
            SseEmitter emitter,
            AtomicBoolean open,
            BggRecommendationAgentController.RecommendationConversationRequest request,
            ConversationRequest command,
            String locale,
            String modelConfigurationOwner) {
        try {
            var presented = request.clientTurnId() != null && conversations != null
                    ? BggRecommendationAgentController.present(
                            conversations.converse(
                                    request.toSessionTurn(command),
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    text -> sendAnswerPart(emitter, open, text)),
                            presentation)
                    : BggRecommendationAgentController.present(
                            agent.converse(
                                    command,
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update),
                                    text -> sendAnswerPart(emitter, open, text)),
                            locale,
                            presentation);
            if (!open.get()) return;
            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(presented));
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
                    code,
                    exception);
            sendError(emitter, open, code);
        }
    }

    private void sendAgentProgress(SseEmitter emitter, AtomicBoolean open, ProgressUpdate update) {
        if (update.stage() == BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST
                && update.phase() == BoardGameRecommendationAgent.ProgressPhase.STARTED) return;
        sendProgress(emitter, open, update);
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
                            ProgressFocusResponse.from(update.focus()),
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

    private void sendAnswerPart(SseEmitter emitter, AtomicBoolean open, String text) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("answer_part")
                    .data(new AnswerPart(text == null ? "" : text)));
        } catch (IOException | RuntimeException exception) {
            open.set(false);
            LOGGER.debug("Recommendation answer stream disconnected before completion");
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean open, String code) {
        if (!open.getAndSet(false)) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(new StreamError(code)));
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
            ProgressFocusResponse focus,
            long elapsedMs,
            int observedCandidates,
            int verifiedCandidates,
            int hardRejectedCandidates,
            int sourceCount) {}

    record ProgressFocusResponse(String kind, List<String> values) {
        static ProgressFocusResponse from(BoardGameRecommendationAgent.ProgressFocus focus) {
            return focus == null
                    ? null
                    : new ProgressFocusResponse(focus.kind().name().toLowerCase(Locale.ROOT), focus.values());
        }
    }

    record StreamError(String code) {}

    record AnswerPart(String text) {}
}
