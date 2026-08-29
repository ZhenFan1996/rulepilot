package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.application.DocumentNativeToolAccess;
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
import java.time.Duration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/document-versions/{versionId}/answers")
public class StructuredRuleAnswerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredRuleAnswerController.class);
    private static final Duration STREAM_COMPLETION_GRACE = Duration.ofSeconds(5);

    private final StructuredRuleAnswerService answers;
    private final GameSessionContextLookup sessions;
    private final GameSessionConversationService conversations;
    private final AnswerFeedbackService feedback;
    private final TaskExecutor streamExecutor;
    private final AssistantRuns runs;
    private final DocumentNativeToolAccess documentAccess;

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers,
            GameSessionContextLookup sessions,
            GameSessionConversationService conversations,
            AnswerFeedbackService feedback,
            AssistantRuns runs,
            DocumentNativeToolAccess documentAccess,
            @Qualifier("structuredRuleAnswerStreamExecutor") TaskExecutor streamExecutor) {
        this.answers = answers;
        this.sessions = sessions;
        this.conversations = conversations;
        this.feedback = feedback;
        this.runs = runs;
        this.documentAccess = documentAccess;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AnswerResponse answer(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        String username = requireOwnedReadyVersion(versionId, principal);
        return createAnswer(versionId, request, username, ignored -> {});
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter answerStream(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        String username = requireOwnedReadyVersion(versionId, principal);
        SseEmitter emitter = runBoundEmitter();
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        send(emitter, open, "accepted", new StreamAccepted("answer_received"));
        if (!open.get()) return emitter;
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
                                        requestLocale(request.language()),
                                        request.question());
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
                    sendError(emitter, open, streamFailure(exception, request));
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Structured answer stream executor is unavailable: {}",
                    exception.getClass().getSimpleName());
            sendError(emitter, open, serviceUnavailable(request));
        }
        return emitter;
    }

    private String requireOwnedReadyVersion(UUID versionId, Principal principal) {
        String username = principal == null ? "" : principal.getName();
        if (!documentAccess.canReadOwnedReadyVersion(username, versionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document version does not exist");
        }
        return username;
    }

    private AnswerResponse createAnswer(
            UUID versionId,
            AnswerRequest request,
            String username,
            Consumer<UUID> runStarted) {
        validateAnswerRequest(request);
        var session = validateSession(request.gameSessionId(), versionId, username);
        var priorTurn = session == null
                ? null
                : conversations.priorTurnReference(session.sessionId(), username, versionId).orElse(null);
        PlayerLocale outputLanguage = requestLocale(request.language());
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

    static SseEmitter runBoundEmitter() {
        // The activity pump closes this stream from the persisted run deadline. A second servlet timeout would create
        // an independent owner that can disconnect while the answer run is still valid.
        return new SseEmitter(0L);
    }

    static Instant streamCompletionDeadline(Instant runDeadline) {
        return runDeadline.plus(STREAM_COMPLETION_GRACE);
    }

    static boolean streamCompletionDeadlineReached(Instant runDeadline, Instant now) {
        return !now.isBefore(streamCompletionDeadline(runDeadline));
    }

    static PlayerActivity playerActivity(ActivitySnapshot activity, PlayerLocale locale) {
        String operation = activity.operation();
        String actor = "answer_agent";
        String stage;
        boolean correctionInProgress = activity.type() == ActivityType.VALIDATION
                && activity.outcome() == ActivityOutcome.REJECTED
                && isRecoverableValidation(operation);
        boolean observationStalled = activity.type() == ActivityType.VALIDATION
                && activity.outcome() == ActivityOutcome.REJECTED
                && operation.startsWith("nativeObservationNoProgress");
        if (correctionInProgress) {
            actor = "answer_validator";
            stage = "correcting_answer";
        } else if (observationStalled) {
            actor = "answer_validator";
            stage = "evidence_search_stalled";
        } else if (operation.startsWith("nativeTool|search_rule_evidence") || operation.equals("hybridRuleSearch")) {
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
        } else if (activity.type() == ActivityType.MODEL) {
            stage = "composing_answer";
        } else if (activity.type() == ActivityType.CRITIC) {
            actor = "answer_reviewer";
            stage = "reviewing_support";
        } else if (activity.type() == ActivityType.VALIDATION) {
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
            case "correcting_answer" -> english
                    ? "The draft did not pass validation; the answer agent is correcting it"
                    : "回答草稿未通过校验，答疑助手正在修正";
            case "evidence_search_stalled" -> english
                    ? "Supplementary evidence search stopped because the same tool call returned the same evidence twice; the answer can continue with evidence already checked"
                    : "相同工具调用连续两次返回完全相同的证据，已停止补充查找；答疑会继续使用已有的核验证据";
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
            case "correcting_answer" -> english
                    ? "Next: retry with a valid action or supported answer"
                    : "下一步：用有效操作或有依据的回答继续";
            case "evidence_search_stalled" -> english
                    ? "Next: compose and validate from the evidence already checked"
                    : "下一步：根据已有核验证据组织并校验回答";
            default -> english
                    ? "Next: publish the supported answer"
                    : "下一步：发布有依据的回答";
        };
        String status = correctionInProgress
                ? "running"
                : activity.outcome().name().toLowerCase(java.util.Locale.ROOT);
        return new PlayerActivity(
                activity.sequence(),
                actor,
                stage,
                message,
                status,
                nextAction,
                activity.latencyMs());
    }

    private static boolean isRecoverableValidation(String operation) {
        return operation.equals("nativeCompletionRequirement")
                || operation.equals("nativeEmptyCompletion")
                || operation.equals("nativeCompletionProtocol")
                || operation.equals("nativeActionProtocol")
                || operation.equals("nativeToolSchema")
                || operation.startsWith("nativeObs|");
    }

    private final class PlayerActivityPump {
        private final SseEmitter emitter;
        private final AtomicBoolean open;
        private final UUID runId;
        private final String username;
        private final PlayerLocale locale;
        private final String question;
        private final Map<Long, String> deliveredStatuses = new HashMap<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private PlayerActivityPump(
                SseEmitter emitter,
                AtomicBoolean open,
                UUID runId,
                String username,
                PlayerLocale locale,
                String question) {
            this.emitter = emitter;
            this.open = open;
            this.runId = runId;
            this.username = username;
            this.locale = locale;
            this.question = safeQuestion(question);
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
            runs.findOwned(runId, username).ifPresent(details -> {
                details.activities().stream()
                        .filter(activity -> !activity.outcome().name().equals(
                                deliveredStatuses.get(activity.sequence())))
                        .forEach(activity -> {
                            deliveredStatuses.put(activity.sequence(), activity.outcome().name());
                            send(emitter, open, "activity", playerActivity(activity, locale));
                        });
                Instant runDeadline = details.budget() == null ? null : details.budget().deadlineAt();
                if (runDeadline != null && streamCompletionDeadlineReached(runDeadline, Instant.now())) {
                    finished.set(true);
                    sendError(emitter, open, timeout(question, locale));
                }
            });
        }
    }

    static StreamError streamFailure(RuntimeException exception, AnswerRequest request) {
        PlayerLocale locale = failureLocale(request);
        String question = safeQuestion(request == null ? null : request.question());
        if (exception instanceof InvalidAnswerContextException) {
            return contextInvalid(question, locale);
        }
        return workflowFailed(question, locale);
    }

    static StreamError serviceUnavailable(AnswerRequest request) {
        return serviceUnavailable(
                safeQuestion(request == null ? null : request.question()), failureLocale(request));
    }

    static StreamError timeout(String question, PlayerLocale locale) {
        boolean english = locale == PlayerLocale.EN;
        return new StreamError(
                "answer_timeout",
                new PlayerFacingRuleAnswer.Recovery(
                        english
                                ? "This answer did not finish within its run limit and has stopped. You can retry the same question; if it times out again, narrow its scope."
                                : "本次答疑未能在运行时限内完成，现已停止。可以原样重试；若再次超时，请缩小问题范围。",
                        english ? "Retry this question" : "重试这个问题",
                        safeQuestion(question),
                        true));
    }

    private static StreamError contextInvalid(String question, PlayerLocale locale) {
        boolean english = locale == PlayerLocale.EN;
        return new StreamError(
                "answer_context_invalid",
                new PlayerFacingRuleAnswer.Recovery(
                        english
                                ? "This answer context is no longer valid or belongs to a different rulebook. Reopen Q&A from the rulebook or lesson page before asking again."
                                : "当前答疑上下文已失效，或与这本规则书不匹配。请从规则书或教学页重新进入答疑后再提问。",
                        english ? "Reopen Q&A" : "重新进入答疑",
                        question,
                        false));
    }

    private static StreamError serviceUnavailable(String question, PlayerLocale locale) {
        boolean english = locale == PlayerLocale.EN;
        return new StreamError(
                "answer_service_unavailable",
                new PlayerFacingRuleAnswer.Recovery(
                        english
                                ? "The answer service is temporarily busy and this question has not started. You can retry it unchanged after the service recovers."
                                : "答疑服务暂时繁忙，这个问题尚未开始处理。服务恢复后可以原样重试。",
                        english ? "Retry later" : "稍后重试",
                        question,
                        true));
    }

    private static StreamError workflowFailed(String question, PlayerLocale locale) {
        boolean english = locale == PlayerLocale.EN;
        return new StreamError(
                "answer_workflow_failed",
                new PlayerFacingRuleAnswer.Recovery(
                        english
                                ? "This answer stopped because an internal workflow failed. The question may not have been processed completely; review or revise it before trying again."
                                : "本次答疑因内部流程错误而停止，问题可能没有被完整处理。请检查或改写后再试。",
                        english ? "Review question" : "检查问题",
                        question,
                        false));
    }

    private static PlayerLocale failureLocale(AnswerRequest request) {
        try {
            return PlayerLocale.fromRequest(request == null ? null : request.language());
        } catch (IllegalArgumentException ignored) {
            return PlayerLocale.ZH_CN;
        }
    }

    private static String safeQuestion(String question) {
        return question == null ? "" : question;
    }

    private static void validateAnswerRequest(AnswerRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new InvalidAnswerContextException("answer question is required");
        }
    }

    private static PlayerLocale requestLocale(String language) {
        try {
            return PlayerLocale.fromRequest(language);
        } catch (IllegalArgumentException invalidLanguage) {
            throw new InvalidAnswerContextException("answer language is unsupported", invalidLanguage);
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean open, StreamError error) {
        if (!open.getAndSet(false)) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(error));
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
                .orElseThrow(() -> new InvalidAnswerContextException("game session does not exist"));
        if (!session.documentVersionId().equals(documentVersionId)) {
            throw new InvalidAnswerContextException("game session uses a different document version");
        }
        return session;
    }

    static final class InvalidAnswerContextException extends IllegalArgumentException {
        InvalidAnswerContextException(String message) {
            super(message);
        }

        InvalidAnswerContextException(String message, Throwable cause) {
            super(message, cause);
        }
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

    record StreamError(String code, PlayerFacingRuleAnswer.Recovery recovery) {}

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
