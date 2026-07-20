package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.AnswerFeedback;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AnswerFeedbackService {

    private final GameSessionConversationRepository conversations;
    private final AnswerFeedbackRepository feedback;
    private final Clock clock = Clock.systemUTC();

    public AnswerFeedbackService(
            GameSessionConversationRepository conversations, AnswerFeedbackRepository feedback) {
        this.conversations = conversations;
        this.feedback = feedback;
    }

    @Transactional
    public AnswerFeedback submit(
            UUID sessionId, UUID conversationTurnId, Rating rating, String username) {
        conversations.findOwned(conversationTurnId, sessionId, username)
                .orElseThrow(() -> new IllegalArgumentException("conversation turn does not exist"));
        AnswerFeedback submitted = AnswerFeedback.create(
                conversationTurnId, sessionId, rating, username, Instant.now(clock));
        UUID persistedId = feedback.save(submitted);
        return new AnswerFeedback(
                persistedId,
                submitted.conversationTurnId(),
                submitted.gameSessionId(),
                submitted.rating(),
                submitted.createdBy(),
                submitted.createdAt());
    }

    @Transactional(readOnly = true)
    public Map<UUID, Rating> ratingsFor(List<GameSessionConversationTurn> turns, String username) {
        Set<UUID> turnIds = turns.stream()
                .map(GameSessionConversationTurn::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return feedback.findRatings(turnIds, username);
    }
}
