package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.application.GameSessionConversationService;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.gamesession.GameSessionContextLookup;
import java.security.Principal;
import java.util.List;
import java.util.Set;
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

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers,
            GameSessionContextLookup sessions,
            GameSessionConversationService conversations) {
        this.answers = answers;
        this.sessions = sessions;
        this.conversations = conversations;
    }

    @PostMapping
    AnswerCreation answer(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        String username = principal.getName();
        var session = validateSession(request.gameSessionId(), versionId, username);
        String previousQuestion = session == null
                ? request.previousQuestion()
                : conversations.previousQuestion(session.sessionId(), username).orElse(null);
        AnswerCreation creation = answers.answerWithRun(
                request.question(),
                new QuestionContext(
                        versionId,
                        request.currentLessonSection(),
                        session == null ? request.gamePhase() : liveTableContext(session),
                        session == null ? request.playerCount() : session.playerCount(),
                        session == null ? request.activeExpansions() : session.expansionIds(),
                        previousQuestion,
                        request.learningIntent()),
                username,
                request.gameSessionId());
        if (session != null) {
            conversations.record(session.sessionId(), request.question(), creation.answer(), username);
        }
        return creation;
    }

    @GetMapping("/conversation")
    List<GameSessionConversationTurn> conversation(
            @PathVariable UUID versionId,
            @RequestParam UUID gameSessionId,
            Principal principal) {
        var session = validateSession(gameSessionId, versionId, principal.getName());
        return conversations.history(session.sessionId(), principal.getName());
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

    private String liveTableContext(GameSessionContextLookup.SessionContext session) {
        String activePlayer = session.activePlayer() == null
                ? "当前玩家未指定"
                : "当前为" + session.activePlayer() + "号玩家";
        return "第" + session.roundNumber() + "轮，" + session.phase() + "，" + activePlayer;
    }

    record AnswerRequest(
            String question,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<UUID> activeExpansions,
            UUID gameSessionId,
            String previousQuestion,
            com.rulepilot.assistant.domain.LearningIntent learningIntent) {}
}
