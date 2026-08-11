package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import java.io.IOException;
import java.security.Principal;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final long STREAM_TIMEOUT_MILLIS = 120_000;

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;
    private final TaskExecutor executor;

    public BggRecommendationAgentStreamController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            @Qualifier("bggRecommendationStreamExecutor") TaskExecutor executor) {
        this.agent = agent;
        this.presentation = presentation;
        this.executor = executor;
    }

    @PostMapping(
            value = "/api/v1/bgg/recommendation-agent/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter converse(
            @RequestBody BggRecommendationAgentController.RecommendationConversationRequest request,
            @RequestParam(defaultValue = "en") String locale,
            Principal principal) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        try {
            String modelConfigurationOwner = principal.getName();
            executor.execute(() -> runConversation(
                    emitter, open, request, locale, modelConfigurationOwner));
        } catch (RuntimeException exception) {
            sendError(emitter, open);
        }
        return emitter;
    }

    private void runConversation(
            SseEmitter emitter,
            AtomicBoolean open,
            BggRecommendationAgentController.RecommendationConversationRequest request,
            String locale,
            String modelConfigurationOwner) {
        try {
            var response = agent.converse(
                    request.toCommand(),
                    locale,
                    modelConfigurationOwner,
                    update -> sendProgress(emitter, open, update));
            if (!open.get()) return;
            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(BggRecommendationAgentController.present(response, locale, presentation)));
            emitter.complete();
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Recommendation stream did not complete: {}", exception.getClass().getSimpleName());
            sendError(emitter, open);
        }
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
            throw new ProgressDisconnectedException(exception);
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean open) {
        if (!open.getAndSet(false)) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(new StreamError("recommendation_unavailable")));
        } catch (IOException | RuntimeException ignored) {
            // The client may already have closed the stream.
        } finally {
            emitter.complete();
        }
    }

    record ProgressResponse(String stage, long elapsedMs) {}

    record StreamError(String code) {}

    private static final class ProgressDisconnectedException extends RuntimeException {
        private ProgressDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
