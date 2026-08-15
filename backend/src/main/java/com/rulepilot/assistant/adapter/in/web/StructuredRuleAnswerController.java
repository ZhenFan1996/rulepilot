package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.application.GameSessionConversationService;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.gamesession.GameSessionContextLookup;
import com.rulepilot.assistant.PlayerLocale;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/document-versions/{versionId}/answers")
public class StructuredRuleAnswerController {

    private final StructuredRuleAnswerService answers;
    private final GameSessionContextLookup sessions;
    private final GameSessionConversationService conversations;
    private final AnswerFeedbackService feedback;

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers,
            GameSessionContextLookup sessions,
            GameSessionConversationService conversations,
            AnswerFeedbackService feedback) {
        this.answers = answers;
        this.sessions = sessions;
        this.conversations = conversations;
        this.feedback = feedback;
    }

    @PostMapping
    AnswerResponse answer(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        String username = principal.getName();
        var session = validateSession(request.gameSessionId(), versionId, username);
        var priorTurn = session == null
                ? null
                : conversations.priorTurnReference(session.sessionId(), username, versionId).orElse(null);
        AnswerCreation creation = answers.answerWithRun(
                request.question(),
                new QuestionContext(
                        versionId,
                        request.previousQuestion(),
                        request.learningIntent(),
                        PlayerLocale.fromRequest(request.language()),
                        priorTurn),
                username,
                request.gameSessionId());
        GameSessionConversationTurn turn = session == null
                ? null
                : conversations.record(session.sessionId(), request.question(), creation.answer(), username);
        return new AnswerResponse(creation.assistantRunId(), creation.answer(), turn == null ? null : turn.id());
    }

    @GetMapping("/conversation")
    List<ConversationTurnResponse> conversation(
            @PathVariable UUID versionId,
            @RequestParam UUID gameSessionId,
            Principal principal) {
        String username = principal.getName();
        var session = validateSession(gameSessionId, versionId, username);
        List<GameSessionConversationTurn> turns = conversations.history(session.sessionId(), username);
        var ratings = feedback.ratingsFor(turns, username);
        return turns.stream()
                .map(turn -> new ConversationTurnResponse(
                        turn.id(), turn.question(), turn.answer(), turn.createdAt(), ratings.get(turn.id())))
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
            UUID assistantRunId,
            StructuredRuleAnswer answer,
            UUID conversationTurnId) {}

    record ConversationTurnResponse(
            UUID id, String question, StructuredRuleAnswer answer, Instant createdAt, Rating feedback) {}
}
