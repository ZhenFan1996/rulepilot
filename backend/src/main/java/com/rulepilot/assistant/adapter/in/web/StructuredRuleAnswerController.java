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
        boolean english = locale == PlayerLocale.EN;
        ActivityProjection projection = projectNativeActivity(activity, english);
        String status = projection.repairInProgress()
                ? "running"
                : activity.outcome().name().toLowerCase(java.util.Locale.ROOT);
        return new PlayerActivity(
                activity.sequence(),
                projection.actor(),
                projection.stage(),
                projection.message(),
                status,
                projection.nextAction(),
                activity.latencyMs());
    }

    private static ActivityProjection projectNativeActivity(ActivitySnapshot activity, boolean english) {
        String operation = activity.operation();
        if (activity.type() == ActivityType.MODEL) {
            return new ActivityProjection(
                    "answer_agent",
                    "model_decision",
                    english
                            ? "The answer Agent is deciding whether to answer or read rulebook evidence"
                            : "答疑 Agent 正在决定直接回答或读取哪些规则书证据",
                    english
                            ? "The same Agent continues from this decision"
                            : "同一 Agent 会从本次决策继续",
                    false);
        }
        if (activity.type() == ActivityType.TOOL && operation.startsWith("nativeTool|")) {
            return new ActivityProjection(
                    "rulebook_tool",
                    "read_tool",
                    readToolMessage(nativeToolName(operation), english),
                    english
                            ? "Its correlated observation returns to the same Agent"
                            : "带调用关联标识的 observation 会返回同一 Agent",
                    false);
        }
        if (operation.startsWith("nativeObs|")) {
            boolean localFailure = activity.outcome() == ActivityOutcome.REJECTED;
            return new ActivityProjection(
                    "rulebook_tool",
                    "tool_observation",
                    localFailure
                            ? (english
                                    ? "This read tool returned a typed local failure; completed sibling observations remain available"
                                    : "本次只读工具返回 typed 局部失败；已完成的同批 observation 仍然可用")
                            : (english
                                    ? "A correlated read-only tool observation returned to the answer Agent"
                                    : "一条带调用关联标识的只读 observation 已返回答疑 Agent"),
                    english
                            ? "The same Agent decides again from all available observations"
                            : "同一 Agent 会基于全部可用 observation 再次决策",
                    false);
        }
        if (isTerminalRepair(operation)) {
            boolean actionRepair = operation.equals("nativeActionProtocol") || operation.equals("nativeToolSchema");
            return new ActivityProjection(
                    "answer_agent",
                    actionRepair ? "repairing_action" : "repairing_terminal",
                    actionRepair
                            ? (english
                                    ? "The complete rejected action and current tool contract returned to the same Agent"
                                    : "被拒绝的完整动作与当前工具合同已返回同一 Agent")
                            : (english
                                    ? "The complete rejected terminal payload and current publication contract returned to the same Agent"
                                    : "被拒绝的完整终态 payload 与当前发布合同已返回同一 Agent"),
                    english
                            ? "The same Agent regenerates the whole payload"
                            : "同一 Agent 会整包重新生成",
                    true);
        }
        if (operation.startsWith("nativeToolFallback|")) {
            String stopCode = operation.substring("nativeToolFallback|".length());
            return new ActivityProjection(
                    "answer_agent",
                    "agent_stopped",
                    stopMessage(stopCode, english),
                    english
                            ? "This answer run ends at the recorded boundary"
                            : "本次答疑运行在该记录边界结束",
                    false);
        }
        if (operation.startsWith("nativeObservationNoProgress|")) {
            return new ActivityProjection(
                    "answer_agent",
                    "agent_stopped",
                    english
                            ? "The answer Agent stopped at OBSERVATION_NO_PROGRESS after repeating the same read and observation"
                            : "答疑 Agent 因重复相同读取并得到相同 observation，在 OBSERVATION_NO_PROGRESS 边界停止",
                    english
                            ? "This answer run ends at the recorded boundary"
                            : "本次答疑运行在该记录边界结束",
                    false);
        }
        return new ActivityProjection(
                "answer_agent",
                "publication_boundary",
                english
                        ? "The native Agent is checking a schema, evidence identity, or resource boundary"
                        : "native Agent 正在检查 schema、证据身份或资源边界",
                english
                        ? "A rejection returns to the same Agent when repair remains allowed"
                        : "若仍允许修复，拒绝结果会返回同一 Agent",
                false);
    }

    private static String nativeToolName(String operation) {
        int start = "nativeTool|".length();
        int end = operation.indexOf('|', start);
        return end < 0 ? operation.substring(start) : operation.substring(start, end);
    }

    private static String readToolMessage(String toolName, boolean english) {
        return switch (toolName) {
            case "search_rule_evidence" -> english
                    ? "The answer Agent called the read-only rulebook search tool"
                    : "答疑 Agent 调用了只读规则书搜索工具";
            case "search_rule_relationships" -> english
                    ? "The answer Agent called the read-only rule relationship search tool"
                    : "答疑 Agent 调用了只读规则关系搜索工具";
            case "expand_rule_evidence_context" -> english
                    ? "The answer Agent called the read-only context expansion tool"
                    : "答疑 Agent 调用了只读上下文扩展工具";
            case "read_rule_pages" -> english
                    ? "The answer Agent called the exact-page read tool"
                    : "答疑 Agent 调用了规则书原页读取工具";
            case "read_visual_page_facts", "read_rule_page_image", "crop_rule_page_image" -> english
                    ? "The answer Agent called a read-only visual rulebook tool"
                    : "答疑 Agent 调用了只读规则书视觉工具";
            default -> english
                    ? "The answer Agent called an allow-listed read-only tool"
                    : "答疑 Agent 调用了 allow-list 内的只读工具";
        };
    }

    private static boolean isTerminalRepair(String operation) {
        return operation.startsWith("nativeCompletionRejection|")
                || operation.equals("nativeCompletionRequirement")
                || operation.equals("nativeEmptyCompletion")
                || operation.equals("nativeCompletionProtocol")
                || operation.equals("nativeActionProtocol")
                || operation.equals("nativeToolSchema");
    }

    private static String stopMessage(String code, boolean english) {
        return switch (code) {
            case "TIMEOUT" -> english
                    ? "The answer Agent stopped at the TIMEOUT resource boundary"
                    : "答疑 Agent 在 TIMEOUT 资源边界停止";
            case "MODEL_REQUEST_TIMEOUT" -> english
                    ? "The answer model request stopped at the MODEL_REQUEST_TIMEOUT boundary"
                    : "答疑模型请求因超时在 MODEL_REQUEST_TIMEOUT 边界停止";
            case "MODEL_REQUEST_UNAVAILABLE" -> english
                    ? "The answer model or provider was temporarily unavailable at the MODEL_REQUEST_UNAVAILABLE boundary"
                    : "答疑模型或模型提供方暂时不可用，已在 MODEL_REQUEST_UNAVAILABLE 边界停止";
            case "CANCELLED" -> english
                    ? "The answer Agent stopped because the owner cancellation reached the CANCELLED boundary"
                    : "答疑 Agent 因所有者取消到达 CANCELLED 边界而停止";
            case "TOKEN_BUDGET", "TOOL_BUDGET", "MODEL_BUDGET", "STEP_BUDGET",
                    "OBSERVATION_BUDGET_EXHAUSTED", "OBSERVATION_BUDGET_EXCEEDED" -> english
                    ? "The answer Agent stopped at the " + code + " resource boundary"
                    : "答疑 Agent 在 " + code + " 资源边界停止";
            case "COMPLETION_NO_PROGRESS", "ACTION_NO_PROGRESS", "OBSERVATION_NO_PROGRESS" -> english
                    ? "The answer Agent stopped at " + code + " after the same rejected result made no progress"
                    : "答疑 Agent 因相同拒绝结果没有进展，在 " + code + " 边界停止";
            case "TOOL_ALLOWLIST_UNAVAILABLE", "MODEL_CAPABILITY_UNAVAILABLE", "EXECUTION_FAILED" -> english
                    ? "The answer Agent stopped at the " + code + " execution boundary"
                    : "答疑 Agent 在 " + code + " 执行边界停止";
            default -> english
                    ? "The answer Agent stopped at the EXECUTION_FAILED boundary"
                    : "答疑 Agent 在 EXECUTION_FAILED 边界停止";
        };
    }

    private record ActivityProjection(
            String actor,
            String stage,
            String message,
            String nextAction,
            boolean repairInProgress) {}

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
