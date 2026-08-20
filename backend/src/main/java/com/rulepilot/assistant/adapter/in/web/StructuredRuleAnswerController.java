package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.application.GameSessionConversationService;
import com.rulepilot.assistant.application.PlayerFacingAnswerPresenter;
import com.rulepilot.assistant.application.PlayerFacingRuleAnswer;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.gamesession.GameSessionContextLookup;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/document-versions/{versionId}/answers")
public class StructuredRuleAnswerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 40_000;

    private final StructuredRuleAnswerService answers;
    private final GameSessionContextLookup sessions;
    private final GameSessionConversationService conversations;
    private final AnswerFeedbackService feedback;
    private final TaskExecutor streamExecutor;

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers,
            GameSessionContextLookup sessions,
            GameSessionConversationService conversations,
            AnswerFeedbackService feedback,
            @Qualifier("structuredRuleAnswerStreamExecutor") TaskExecutor streamExecutor) {
        this.answers = answers;
        this.sessions = sessions;
        this.conversations = conversations;
        this.feedback = feedback;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AnswerResponse answer(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        return createAnswer(versionId, request, principal.getName(), ignored -> {});
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter answerStream(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        send(emitter, open, "accepted", new StreamAccepted("answer_received"));
        if (!open.get()) return emitter;
        String username = principal.getName();
        try {
            streamExecutor.execute(() -> {
                try {
                    AnswerResponse response = createAnswer(
                            versionId,
                            request,
                            username,
                            runId -> send(emitter, open, "run", new StreamRun(runId)));
                    if (!open.get()) return;
                    send(emitter, open, "result", response);
                    if (open.getAndSet(false)) emitter.complete();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Structured answer stream did not complete: {}", exception.getClass().getSimpleName());
                    sendError(emitter, open, "answer_unavailable");
                }
            });
        } catch (RuntimeException exception) {
            sendError(emitter, open, "answer_unavailable");
        }
        return emitter;
    }

    private AnswerResponse createAnswer(
            UUID versionId,
            AnswerRequest request,
            String username,
            Consumer<UUID> runStarted) {
        var session = validateSession(request.gameSessionId(), versionId, username);
        var priorTurn = session == null
                ? null
                : conversations.priorTurnReference(session.sessionId(), username, versionId).orElse(null);
        PlayerLocale outputLanguage = PlayerLocale.forQuestion(
                request.question(), PlayerLocale.fromRequest(request.language()));
        AnswerCreation creation = answers.answerWithRun(
                request.question(),
                new QuestionContext(
                        versionId,
                        request.previousQuestion(),
                        request.learningIntent(),
                        outputLanguage,
                        priorTurn),
                username,
                request.gameSessionId(),
                runStarted);
        GameSessionConversationTurn turn = session == null
                ? null
                : conversations.record(session.sessionId(), request.question(), creation.answer(), username);
        return new AnswerResponse(
                PlayerFacingAnswerPresenter.present(creation.answer(), request.question(), outputLanguage),
                turn == null ? null : turn.id(),
                RulingReference.from(creation.answer()));
    }

    private void send(SseEmitter emitter, AtomicBoolean open, String event, Object data) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | RuntimeException exception) {
            open.set(false);
            LOGGER.debug("Structured answer stream disconnected before completion");
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean open, String code) {
        if (!open.getAndSet(false)) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(new StreamError(code)));
        } catch (IOException | RuntimeException ignored) {
            // The client may already have closed the stream.
        } finally {
            emitter.complete();
        }
    }

    @GetMapping("/conversation")
    List<ConversationTurnResponse> conversation(
            @PathVariable UUID versionId,
            @RequestParam UUID gameSessionId,
            @RequestParam(defaultValue = "zh-CN") String language,
            Principal principal) {
        String username = principal.getName();
        var session = validateSession(gameSessionId, versionId, username);
        List<GameSessionConversationTurn> turns = conversations.history(session.sessionId(), username);
        var ratings = feedback.ratingsFor(turns, username);
        PlayerLocale requestedLanguage = PlayerLocale.fromRequest(language);
        return turns.stream()
                .map(turn -> new ConversationTurnResponse(
                        turn.id(),
                        turn.question(),
                        PlayerFacingAnswerPresenter.present(turn.answer(), turn.question(), requestedLanguage),
                        turn.createdAt(),
                        ratings.get(turn.id()),
                        RulingReference.from(turn.answer())))
                .toList();
    }

    private GameSessionContextLookup.SessionContext validateSession(
            UUID sessionId, UUID documentVersionId, String username) {
        if (sessionId == null) {
            return null;
        }
        var session = sessions.findOwned(sessionId, username)
                .orElseThrow(() -> new IllegalArgumentException("game session does not exist"));
        if (!session.documentVersionId().equals(documentVersionId)) {
            throw new IllegalArgumentException("game session uses a different document version");
        }
        return session;
    }

    record AnswerRequest(
            String question,
            UUID gameSessionId,
            String previousQuestion,
            com.rulepilot.assistant.domain.LearningIntent learningIntent,
            String language) {}

    record AnswerResponse(
            PlayerFacingRuleAnswer answer,
            UUID conversationTurnId,
            RulingReference rulingReference) {}

    record StreamAccepted(String state) {}

    record StreamRun(UUID runId) {}

    record StreamError(String code) {}

    record ConversationTurnResponse(
            UUID id,
            String question,
            PlayerFacingRuleAnswer answer,
            Instant createdAt,
            Rating feedback,
            RulingReference rulingReference) {}

    /** Operational references for explicit save/edit actions; these are never part of player-visible answer content. */
    record RulingReference(
            List<UUID> citationIds,
            UUID confirmedRulingId,
            Long confirmedRulingVersion) {

        static RulingReference from(StructuredRuleAnswer answer) {
            return new RulingReference(
                    answer.citations().stream().map(citation -> citation.chunkId()).toList(),
                    answer.confirmedRulingId(),
                    answer.confirmedRulingVersion());
        }

        RulingReference {
            citationIds = List.copyOf(citationIds);
        }
    }
}
