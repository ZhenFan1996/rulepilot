package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.application.AnswerFeedbackService;
import com.rulepilot.assistant.domain.AnswerFeedback;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import com.rulepilot.gamesession.GameSessionContextLookup;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/game-sessions/{sessionId}/conversation/{turnId}/feedback")
public class AnswerFeedbackController {

    private final GameSessionContextLookup sessions;
    private final AnswerFeedbackService feedback;

    public AnswerFeedbackController(GameSessionContextLookup sessions, AnswerFeedbackService feedback) {
        this.sessions = sessions;
        this.feedback = feedback;
    }

    @PutMapping
    AnswerFeedback submit(
            @PathVariable UUID sessionId,
            @PathVariable UUID turnId,
            @RequestBody FeedbackRequest request,
            Principal principal) {
        sessions.findOwned(sessionId, principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("game session does not exist"));
        return feedback.submit(sessionId, turnId, request.rating(), principal.getName());
    }

    record FeedbackRequest(Rating rating) {}
}
