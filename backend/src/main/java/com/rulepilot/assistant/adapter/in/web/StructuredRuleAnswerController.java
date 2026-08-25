package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AssistantRuns;
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
import com.rulepilot.shared.AsyncContextPropagation;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final AssistantRuns runs;

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers,
            GameSessionContextLookup sessions,
            GameSessionConversationService conversations,
            AnswerFeedbackService feedback,
            AssistantRuns runs,
            @Qualifier("structuredRuleAnswerStreamExecutor") TaskExecutor streamExecutor) {
        this.answers = answers;
        this.sessions = sessions;
        this.conversations = conversations;
        this.feedback = feedback;
        this.runs = runs;
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
                PlayerActivityPump[] activityPump = new PlayerActivityPump[1];
                try {
                    AnswerResponse response = createAnswer(
                            versionId,
                            request,
                            username,
                            runId -> {
                                send(emitter, open, "run", new StreamRun(runId));
                                activityPump[0] = new PlayerActivityPump(
                                        emitter,
                                        open,
                                        runId,
                                        username,
                                        PlayerLocale.forQuestion(
                                                request.question(), PlayerLocale.fromRequest(request.language())));
                                activityPump[0].start();
                            });
                    if (activityPump[0] != null) activityPump[0].finish();
                    if (!open.get()) return;
                    send(emitter, open, "answer_part", new AnswerPart("verdict", response.answer().shortVerdict()));
                    send(emitter, open, "answer_part", new AnswerPart("explanation", response.answer().explanation()));
                    send(emitter, open, "result", response);
                    if (open.getAndSet(false)) emitter.complete();
                } catch (RuntimeException exception) {
                    if (activityPump[0] != null) activityPump[0].finish();
                    LOGGER.warn(
                            "Structured answer stream did not complete: {}: {}",
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            exception);
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
        synchronized (emitter) {
            if (!open.get()) return;
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | RuntimeException exception) {
                open.set(false);
                LOGGER.debug("Structured answer stream disconnected before completion");
            }
        }
    }

    static PlayerActivity playerActivity(ActivitySnapshot activity, PlayerLocale locale) {
        String operation = activity.operation();
        String actor = "answer_agent";
        String stage;
        if (operation.startsWith("nativeTool|search_rule_evidence") || operation.equals("hybridRuleSearch")) {
            actor = "rulebook_search";
            stage = "searching_evidence";
        } else if (operation.startsWith("nativeTool|search_rule_relationships")) {
            actor = "rulebook_search";
            stage = "checking_exceptions";
        } else if (operation.startsWith("nativeTool|expand_rule_evidence_context")) {
            actor = "rulebook_reader";
            stage = "expanding_context";
        } else if (operation.startsWith("nativeTool|read_rule_pages")) {
            actor = "rulebook_reader";
            stage = "reading_pages";
        } else if (activity.type() == com.rulepilot.assistant.AgentExecutionControl.ActivityType.MODEL) {
            stage = "composing_answer";
        } else if (activity.type() == com.rulepilot.assistant.AgentExecutionControl.ActivityType.CRITIC) {
            actor = "answer_reviewer";
            stage = "reviewing_support";
        } else if (activity.type() == com.rulepilot.assistant.AgentExecutionControl.ActivityType.VALIDATION) {
            actor = "answer_validator";
            stage = "validating_citations";
        } else {
            actor = "rulebook_tool";
            stage = "checking_rule_details";
        }
        boolean english = locale == PlayerLocale.EN;
        String message = switch (stage) {
            case "searching_evidence" -> english
                    ? "Searching the indexed rulebook for direct evidence"
                    : "正在规则书索引中查找直接依据";
            case "checking_exceptions" -> english
                    ? "Checking exception and override clauses"
                    : "正在核对例外与覆盖条款";
            case "expanding_context" -> english
                    ? "Reading the context around the matched citation"
                    : "正在阅读命中引用前后的完整语境";
            case "reading_pages" -> english
                    ? "Reading the exact rulebook pages"
                    : "正在读取对应的规则书原页";
            case "composing_answer" -> english
                    ? "Composing only from verified evidence"
                    : "正在只根据已核实证据组织回答";
            case "reviewing_support" -> english
                    ? "Reviewing whether each conclusion is supported"
                    : "正在复核每个结论是否有依据";
            case "validating_citations" -> english
                    ? "Validating citation ownership and page boundaries"
                    : "正在校验引用归属与页码边界";
            default -> english ? "Checking a rule-specific detail" : "正在核对具体规则细节";
        };
        String nextAction = switch (stage) {
            case "searching_evidence", "checking_exceptions" -> english
                    ? "Next: read the strongest matching rule in context"
                    : "下一步：阅读最强命中规则的上下文";
            case "expanding_context", "reading_pages" -> english
                    ? "Next: verify what the cited text actually supports"
                    : "下一步：核对原文实际支持的结论边界";
            case "composing_answer" -> english
                    ? "Next: validate the answer and citations"
                    : "下一步：校验回答与引用";
            default -> english
                    ? "Next: publish the supported answer"
                    : "下一步：发布有依据的回答";
        };
        return new PlayerActivity(
                activity.sequence(),
                actor,
                stage,
                message,
                activity.outcome().name().toLowerCase(java.util.Locale.ROOT),
                nextAction,
                activity.latencyMs());
    }

    private final class PlayerActivityPump {
        private final SseEmitter emitter;
        private final AtomicBoolean open;
        private final UUID runId;
        private final String username;
        private final PlayerLocale locale;
        private final Map<Long, String> deliveredStatuses = new HashMap<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private PlayerActivityPump(
                SseEmitter emitter,
                AtomicBoolean open,
                UUID runId,
                String username,
                PlayerLocale locale) {
            this.emitter = emitter;
            this.open = open;
            this.runId = runId;
            this.username = username;
            this.locale = locale;
        }

        private void start() {
            Thread.startVirtualThread(AsyncContextPropagation.runnable(() -> {
                while (open.get() && !finished.get()) {
                    flush();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }

        private void finish() {
            finished.set(true);
            flush();
        }

        private synchronized void flush() {
            if (!open.get()) return;
            runs.findOwned(runId, username).ifPresent(details -> details.activities().stream()
                    .filter(activity -> !activity.outcome().name().equals(
                            deliveredStatuses.get(activity.sequence())))
                    .forEach(activity -> {
                        deliveredStatuses.put(activity.sequence(), activity.outcome().name());
                        send(emitter, open, "activity", playerActivity(activity, locale));
                    }));
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

    record PlayerActivity(
            long sequence,
            String actor,
            String stage,
            String message,
            String status,
            String nextAction,
            long latencyMs) {}

    record AnswerPart(String field, String text) {}

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
