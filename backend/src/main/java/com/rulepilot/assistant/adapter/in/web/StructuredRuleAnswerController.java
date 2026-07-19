package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.gamesession.GameSessionContextLookup;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/document-versions/{versionId}/answers")
public class StructuredRuleAnswerController {

    private final StructuredRuleAnswerService answers;
    private final GameSessionContextLookup sessions;

    public StructuredRuleAnswerController(
            StructuredRuleAnswerService answers, GameSessionContextLookup sessions) {
        this.answers = answers;
        this.sessions = sessions;
    }

    @PostMapping
    AnswerCreation answer(
            @PathVariable UUID versionId, @RequestBody AnswerRequest request, Principal principal) {
        validateSession(request.gameSessionId(), versionId, principal.getName());
        return answers.answerWithRun(
                request.question(),
                new QuestionContext(
                        versionId, request.currentLessonSection(), request.gamePhase(),
                        request.playerCount(), request.activeExpansions(), request.previousQuestion(),
                        request.learningIntent()),
                principal.getName(),
                request.gameSessionId());
    }

    private void validateSession(UUID sessionId, UUID documentVersionId, String username) {
        if (sessionId == null) {
            return;
        }
        var session = sessions.findOwned(sessionId, username)
                .orElseThrow(() -> new IllegalArgumentException("game session does not exist"));
        if (!session.documentVersionId().equals(documentVersionId)) {
            throw new IllegalArgumentException("game session uses a different document version");
        }
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
