package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationException;
import java.io.IOException;
import java.security.Principal;
import java.util.Locale;
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
    private static final long STREAM_TIMEOUT_MILLIS = 35_000;

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;
    private final TaskExecutor executor;
    private final RecommendationConversationCoordinator conversations;

    @Autowired
    public BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            @Qualifier("bggRecommendationStreamExecutor") TaskExecutor executor,
            RecommendationConversationCoordinator conversations) {
        this.agent = agent;
        this.presentation = presentation;
        this.executor = executor;
        this.conversations = conversations;
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
            Principal principal) {
        ConversationRequest command = request.toCommand();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        sendProgress(emitter, open, new ProgressUpdate(
                BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST, 0));
        if (!open.get()) return emitter;
        try {
            String modelConfigurationOwner = principal.getName();
            executor.execute(() -> runConversation(
                    emitter, open, request, command, locale, modelConfigurationOwner));
        } catch (RuntimeException exception) {
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
                                    update -> sendAgentProgress(emitter, open, update)),
                            presentation)
                    : BggRecommendationAgentController.present(
                            agent.converse(
                                    command,
                                    locale,
                                    modelConfigurationOwner,
                                    update -> sendAgentProgress(emitter, open, update)),
                            locale,
                            presentation);
            if (!open.get()) return;
            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(presented));
            emitter.complete();
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Recommendation stream did not complete: {}", exception.getClass().getSimpleName());
            String code = exception instanceof RecommendationConversationException conversationFailure
                    ? conversationFailure.code().name().toLowerCase(Locale.ROOT)
                    : "recommendation_unavailable";
            sendError(emitter, open, code);
        }
    }

    private void sendAgentProgress(SseEmitter emitter, AtomicBoolean open, ProgressUpdate update) {
        if (update.stage() == BoardGameRecommendationAgent.ProgressStage.UNDERSTANDING_REQUEST) return;
        sendProgress(emitter, open, update);
    }

    private void sendProgress(SseEmitter emitter, AtomicBoolean open, ProgressUpdate update) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new ProgressResponse(
                            update.stage().name().toLowerCase(Locale.ROOT), update.elapsedMs())));
        } catch (IOException | RuntimeException exception) {
            open.set(false);
            LOGGER.debug("Recommendation progress stream disconnected before completion");
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

    record ProgressResponse(String stage, long elapsedMs) {}

    record StreamError(String code) {}
}
